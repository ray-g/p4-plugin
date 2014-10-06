package org.jenkinsci.plugins.p4;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Node;
import hudson.scm.ChangeLogParser;
import hudson.scm.PollingResult;
import hudson.scm.SCMDescriptor;
import hudson.scm.SCMRevisionState;
import hudson.scm.SCM;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

import org.acegisecurity.Authentication;
import org.jenkinsci.plugins.p4.browsers.P4Browser;
import org.jenkinsci.plugins.p4.changes.P4ChangeParser;
import org.jenkinsci.plugins.p4.changes.P4ChangeSet;
import org.jenkinsci.plugins.p4.client.ClientHelper;
import org.jenkinsci.plugins.p4.client.ConnectionHelper;
import org.jenkinsci.plugins.p4.credentials.P4StandardCredentials;
import org.jenkinsci.plugins.p4.filters.Filter;
import org.jenkinsci.plugins.p4.filters.FilterPathImpl;
import org.jenkinsci.plugins.p4.filters.FilterPerChangeImpl;
import org.jenkinsci.plugins.p4.filters.FilterUserImpl;
import org.jenkinsci.plugins.p4.populate.ForceCleanImpl;
import org.jenkinsci.plugins.p4.populate.Populate;
import org.jenkinsci.plugins.p4.tagging.TagAction;
import org.jenkinsci.plugins.p4.workspace.Workspace;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.perforce.p4java.core.file.IFileSpec;
import com.perforce.p4java.exception.AccessException;
import com.perforce.p4java.exception.RequestException;
import com.perforce.p4java.impl.generic.core.Changelist;
import com.perforce.p4java.impl.generic.core.Label;

public class PerforceScm extends SCM {

	private static Logger logger = Logger
			.getLogger(PerforceScm.class.getName());

	private final String credential;
	private final Workspace workspace;
	private final List<Filter> filter;
	private final Populate populate;
	private final P4Browser browser;

	public String getCredential() {
		return credential;
	}

	public Workspace getWorkspace() {
		return workspace;
	}

	public List<Filter> getFilter() {
		return filter;
	}

	public Populate getPopulate() {
		return populate;
	}

	@Override
	public P4Browser getBrowser() {
		return browser;
	}

	/**
	 * Create a constructor that takes non-transient fields, and add the
	 * annotation @DataBoundConstructor to it. Using the annotation helps the
	 * Stapler class to find which constructor that should be used when
	 * automatically copying values from a web form to a class.
	 */
	@DataBoundConstructor
	public PerforceScm(String credential, Workspace workspace,
			List<Filter> filter, Populate populate, P4Browser browser) {
		this.credential = credential;
		this.workspace = workspace;
		this.filter = filter;
		this.populate = populate;
		this.browser = browser;
	}

	public PerforceScm(String credential, Workspace workspace, Populate populate) {
		this.credential = credential;
		this.workspace = workspace;
		this.filter = null;
		this.populate = populate;
		this.browser = null;
	}

	/**
	 * Calculate the state of the workspace of the given build. The returned
	 * object is then fed into compareRemoteRevisionWith as the baseline
	 * SCMRevisionState to determine if the build is necessary, and is added to
	 * the build as an Action for later retrieval.
	 */
	@Override
	public SCMRevisionState calcRevisionsFromBuild(AbstractBuild<?, ?> build,
			Launcher launcher, TaskListener listener) throws IOException,
			InterruptedException {
		// Method not required by Perforce
		return null;
	}

	/**
	 * This method does the actual polling and returns a PollingResult. The
	 * change attribute of the PollingResult the significance of the changes
	 * detected by this poll.
	 */
	@Override
	protected PollingResult compareRemoteRevisionWith(
			AbstractProject<?, ?> project, Launcher launcher,
			FilePath buildWorkspace, TaskListener listener,
			SCMRevisionState baseline) throws IOException, InterruptedException {

		PollingResult state = PollingResult.NO_CHANGES;
		PrintStream log = listener.getLogger();

		AbstractBuild<?, ?> build = project.getLastBuild();
		PerforceScm scm = (PerforceScm) build.getProject().getScm();
		String scmCredential = scm.getCredential();
		Populate scmPopulate = scm.getPopulate();
		List<Filter> scmFilter = scm.getFilter();

		// CAUTION: scmWorkspace environment will have limited access to the
		// environment variables (e.g. NODE_NAME is missing). Instead get the
		// expanded client workspace name from the last build.
		String client = "unset";
		try {
			EnvVars envVars = build.getEnvironment(null);
			client = envVars.get("P4_CLIENT");
			log.println("P4: Polling with client: " + client);
		} catch (Exception e) {
			logger.warning("P4: Unable to read P4_CLIENT");
			return PollingResult.NO_CHANGES;
		}

		ClientHelper p4 = new ClientHelper(scmCredential, listener, client);

		try {
			// expand the label if required
			String pin = scmPopulate.getPin();
			Workspace scmWorkspace = setEnvironment(build, listener);

			// find changes...
			List<Object> changes = new ArrayList<Object>();
			if (pin != null && !pin.isEmpty()) {
				pin = scmWorkspace.expand(pin);
				List<Integer> have = p4.listHaveChanges(pin);
				int last = 0;
				if (!have.isEmpty()) {
					last = have.get(have.size() - 1);
				}
				log.println("P4: Polling with label/change: " + last + ","
						+ pin);
				changes = p4.listChanges(last, pin);
			} else {
				List<Integer> have = p4.listHaveChanges();
				int last = 0;
				if (!have.isEmpty()) {
					last = have.get(have.size() - 1);
				}
				log.println("P4: Polling with label/change: " + last + ",now");
				changes = p4.listChanges(last);
			}

			// filter changes...
			List<Integer> remainder = new ArrayList<Integer>();
			for (Object c : changes) {
				if (c instanceof Integer) {
					Changelist changelist = p4.getChange((Integer) c);
					// add unfiltered changes to remainder list
					if (!filterChange(changelist, scmFilter)) {
						remainder.add(changelist.getId());
						log.println("... found change: " + changelist.getId());
					}
				}
			}

			// if there is a remainder...
			if (!remainder.isEmpty()) {
				// if Poll per change, use lowest change to pin build.
				if (scmFilter != null) {
					for (Filter f : scmFilter) {
						if (f instanceof FilterPerChangeImpl) {
							FilterPerChangeImpl perChange = (FilterPerChangeImpl) f;
							if (perChange.isPerChange()) {
								int lowest = remainder
										.get(remainder.size() - 1);
								perChange.setNextChange(lowest);
							}
						}
					}
				}
				state = PollingResult.BUILD_NOW;
			}

			// if the workspace is out of date...
			if (p4.updateFiles()) {
				state = PollingResult.BUILD_NOW;
			}

		} catch (Exception e) {
			logger.severe("P4: Polling Error: " + e);
			e.printStackTrace();
			return null;
		} finally {
			// close connection
			p4.disconnect();
		}
		return state;
	}

	/**
	 * Returns true if change should be filtered
	 * 
	 * @param changelist
	 * @return
	 * @throws AccessException
	 * @throws RequestException
	 * @throws Exception
	 */
	private boolean filterChange(Changelist changelist, List<Filter> scmFilter)
			throws Exception {
		// exit early if no filters
		if (scmFilter == null) {
			return false;
		}

		String user = changelist.getUsername();
		List<IFileSpec> files = changelist.getFiles(true);

		for (Filter f : scmFilter) {
			// Scan through User filters
			if (f instanceof FilterUserImpl) {
				// return is user matches filter
				String u = ((FilterUserImpl) f).getUser();
				if (u.equalsIgnoreCase(user)) {
					return true;
				}
			}

			// Scan through Path filters
			if (f instanceof FilterPathImpl) {
				// add unmatched files to remainder list
				List<IFileSpec> remainder = new ArrayList<IFileSpec>();
				String path = ((FilterPathImpl) f).getPath();
				for (IFileSpec s : files) {
					String p = s.getDepotPathString();
					if (!p.startsWith(path)) {
						remainder.add(s);
					}
				}

				// update files with remainder
				files = remainder;

				// add if all files are removed then remove change
				if (files.isEmpty()) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The checkout method is expected to check out modified files into the
	 * project workspace. In Perforce terms a 'p4 sync' on the project's
	 * workspace. Authorisation
	 */
	@Override
	public boolean checkout(AbstractBuild<?, ?> build, Launcher launcher,
			FilePath buildWorkspace, BuildListener listener, File changelogFile)
			throws IOException, InterruptedException {

		boolean success = true;

		Workspace scmWorkspace = setEnvironment(build, listener);
		scmWorkspace.setRootPath(buildWorkspace.getRemote());
		String scmCredential = getCredential();
		Populate scmPopulate = getPopulate();

		// Create task
		CheckoutTask task;
		task = new CheckoutTask(scmCredential, scmWorkspace, listener);
		task.setPopulateOpts(scmPopulate);
		task.setBuildOpts(scmWorkspace);

		// Add tagging action to build, enabling label support.
		TagAction tag = new TagAction(build);
		tag.setClient(scmWorkspace.getFullName());
		tag.setCredential(scmCredential);
		tag.setBuildChange(task.getBuildChange());
		build.addAction(tag);

		// Invoke build.
		success &= buildWorkspace.act(task);

		// Only write change log if build succeed.
		if (success) {
			// Calculate changes prior to build (based on last build)
			List<Object> changes = calculateChanges(build, task);
			P4ChangeSet.store(changelogFile, changes);
		} else {
			String msg = "P4: Build failed";
			logger.warning(msg);
			throw new AbortException(msg);
		}
		return success;
	}

	private List<Object> calculateChanges(AbstractBuild<?, ?> build,
			CheckoutTask task) {
		List<Object> changes = new ArrayList<Object>();
		AbstractBuild<?, ?> lastBuild = build.getPreviousBuild();
		if (lastBuild != null) {
			TagAction lastTag = lastBuild.getAction(TagAction.class);
			if (lastTag != null) {
				Object lastChange = lastTag.getBuildChange();
				if (lastChange != null) {
					changes = task.getChanges(lastChange);
				}
			}
		} else {
			// No previous build, so add current
			changes.add(task.getBuildChange());
		}
		return changes;
	}

	private Workspace setEnvironment(AbstractBuild<?, ?> build,
			TaskListener listener) throws IOException, InterruptedException {

		Workspace scmWorkspace = (Workspace) getWorkspace().clone();

		// load environments
		EnvVars envVars = build.getEnvironment(listener);
		scmWorkspace.clear();
		scmWorkspace.load(envVars);

		// Set label in map, if pinning is used.
		Populate scmPopulate = getPopulate();
		String pin = scmPopulate.getPin();
		if (pin != null && !pin.isEmpty()) {
			pin = scmWorkspace.expand(pin);
			scmWorkspace.set("label", pin);
		}

		// Set label to next change if perBuild is used
		if (filter != null) {
			for (Filter f : filter) {
				if (f instanceof FilterPerChangeImpl) {
					FilterPerChangeImpl perChange = (FilterPerChangeImpl) f;
					int next = perChange.getNextChange();
					scmWorkspace.set("label", Integer.toString(next));
				}
			}
		}

		return scmWorkspace;
	}

	@Override
	public void buildEnvVars(AbstractBuild<?, ?> build, Map<String, String> env) {
		super.buildEnvVars(build, env);

		TagAction tagAction = build.getAction(TagAction.class);
		if (tagAction != null) {
			// Set P4_CHANGELIST value
			if (tagAction.getBuildChange() != null) {
				String change = getChangeNumber(tagAction);
				env.put("P4_CHANGELIST", change);
			}

			// Set P4_CLIENT workspace value
			if (tagAction.getClient() != null) {
				String client = tagAction.getClient();
				env.put("P4_CLIENT", client);
			}
		}
	}

	private String getChangeNumber(TagAction tagAction) {
		Object buildChange = tagAction.getBuildChange();

		if (buildChange instanceof Integer) {
			// it already an Integer, so add change...
			String change = String.valueOf(buildChange);
			return change;
		}

		try {
			// it is really a change number, so add change...
			int change = Integer.parseInt((String) buildChange);
			return String.valueOf(change);
		} catch (NumberFormatException n) {
		}

		ConnectionHelper p4 = new ConnectionHelper(getCredential(), null);
		String name = (String) buildChange;
		try {
			Label label = p4.getLabel(name);
			String spec = label.getRevisionSpec();
			if (spec != null && !spec.isEmpty()) {
				if (spec.startsWith("@")) {
					spec = spec.substring(1);
				}
				return spec;
			} else {
				// a label, but no RevisionSpec
				return name;
			}
		} catch (Exception e) {
			// not a label
			return name;
		} finally {
			p4.disconnect();
		}
	}

	/**
	 * The checkout method should, besides checking out the modified files,
	 * write a changelog.xml file that contains the changes for a certain build.
	 * The changelog.xml file is specific for each SCM implementation, and the
	 * createChangeLogParser returns a parser that can parse the file and return
	 * a ChangeLogSet.
	 */
	@Override
	public ChangeLogParser createChangeLogParser() {
		return new P4ChangeParser();
	}

	/**
	 * Called before a workspace is deleted on the given node, to provide SCM an
	 * opportunity to perform clean up.
	 */
	@Override
	public boolean processWorkspaceBeforeDeletion(
			AbstractProject<?, ?> project, FilePath buildWorkspace, Node node) {

		PerforceScm scm = (PerforceScm) project.getScm();
		String scmCredential = scm.getCredential();
		AbstractBuild<?, ?> build = project.getLastBuild();

		String client = "unset";
		try {
			EnvVars envVars = build.getEnvironment(null);
			client = envVars.get("P4_CLIENT");
		} catch (Exception e) {
			logger.warning("P4: Unable to read P4_CLIENT");
			return true;
		}

		ClientHelper p4 = new ClientHelper(scmCredential, null, client);
		try {
			ForceCleanImpl forceClean = new ForceCleanImpl(false, null);
			logger.info("P4: unsyncing client: " + client);
			p4.syncFiles(0, forceClean);
		} catch (Exception e) {
			logger.warning("P4: Not able to unsync client: " + client);
			return true;
		} finally {
			p4.disconnect();
		}
		return true;
	}

	/**
	 * Returns the ScmDescriptor<?> for the SCM object. The ScmDescriptor is
	 * used to create new instances of the SCM.
	 */
	@Override
	public SCMDescriptor<PerforceScm> getDescriptor() {
		return (DescriptorImpl) super.getDescriptor();
	}

	public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

	/**
	 * The relationship of Descriptor and SCM (the describable) is akin to class
	 * and object. What this means is that the descriptor is used to create
	 * instances of the describable. Usually the Descriptor is an internal class
	 * in the SCM class named DescriptorImpl. The Descriptor should also contain
	 * the global configuration options as fields, just like the SCM class
	 * contains the configurations options for a job.
	 * 
	 * @author pallen
	 * 
	 */
	@Extension
	public static class DescriptorImpl extends SCMDescriptor<PerforceScm> {

		/**
		 * public no-argument constructor
		 */
		public DescriptorImpl() {
			super(PerforceScm.class, P4Browser.class);
			load();
		}

		@Override
		public SCM newInstance(StaplerRequest req, JSONObject formData)
				throws FormException {
			PerforceScm scm = (PerforceScm) super.newInstance(req, formData);
			return scm;
		}

		/**
		 * Returns the name of the SCM, this is the name that will show up next
		 * to CVS and Subversion when configuring a job.
		 */
		@Override
		public String getDisplayName() {
			return "Perforce Software";
		}

		/**
		 * The configure method is invoked when the global configuration page is
		 * submitted. In the method the data in the web form should be copied to
		 * the Descriptor's fields. To persist the fields to the global
		 * configuration XML file, the save() method must be called. Data is
		 * defined in the global.jelly page.
		 * 
		 */
		@Override
		public boolean configure(StaplerRequest req, JSONObject json)
				throws FormException {
			save();
			return true;
		}

		/**
		 * Credentials list, a Jelly config method for a build job.
		 * 
		 * @return A list of Perforce credential items to populate the jelly
		 *         Select list.
		 */
		public ListBoxModel doFillCredentialItems() {
			ListBoxModel list = new ListBoxModel();

			Class<P4StandardCredentials> type = P4StandardCredentials.class;
			Jenkins scope = Jenkins.getInstance();
			Authentication acl = ACL.SYSTEM;
			DomainRequirement domain = new DomainRequirement();

			List<P4StandardCredentials> credentials;
			credentials = CredentialsProvider.lookupCredentials(type, scope,
					acl, domain);

			if (credentials.isEmpty()) {
				list.add("Select credential...", null);
			}
			for (P4StandardCredentials c : credentials) {
				StringBuffer sb = new StringBuffer();
				sb.append(c.getDescription());
				sb.append(" (");
				sb.append(c.getUsername());
				sb.append(":");
				sb.append(c.getP4port());
				sb.append(")");
				list.add(sb.toString(), c.getId());
			}
			return list;
		}

		public FormValidation doCheckCredential(@QueryParameter String value) {
			if (value == null) {
				return FormValidation.ok();
			}
			try {
				ConnectionHelper p4 = new ConnectionHelper(value, null);
				if (!p4.login()) {
					return FormValidation
							.error("Authentication Error: Unable to login.");
				}
				if (!p4.checkVersion(20121)) {
					return FormValidation
							.error("Server version is too old (min 2012.1)");
				}
				return FormValidation.ok();
			} catch (Exception e) {
				return FormValidation.error(e.getMessage());
			}
		}
	}

	/**
	 * This methods determines if the SCM plugin can be used for polling
	 */
	@Override
	public boolean supportsPolling() {
		return true;
	}

	/**
	 * This method should return true if the SCM requires a workspace for
	 * polling. Perforce however can report submitted, pending and shelved
	 * changes without needing a workspace
	 */
	@Override
	public boolean requiresWorkspaceForPolling() {
		return true;
	}

}
