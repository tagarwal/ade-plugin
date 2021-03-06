package com.oracle.hudson.plugins;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.ProcStarter;
import hudson.Proc;
import hudson.model.BuildListener;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Run.RunnerAbortedException;
import hudson.remoting.Channel;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
//import hudson.model.Cause.*;

/**
 * Everything build step has to happen in an ADE view requires that Hudson know how to "wrap" these commands
 * to run within the context of an ADE view. 
 * 
 * @author jamclark
 *
 */
public class AdeViewLauncherDecorator extends BuildWrapper {
	
	private String viewName;
	private String series;
	private String label;
	private Boolean isTip = false;
	private Boolean shouldDestroyView = true;
	private Boolean useExistingView = false;
	private Boolean isUsingLabel = false;
	private AdeEnvironmentCache environmentCache;
	
	@DataBoundConstructor
	public AdeViewLauncherDecorator(String view, String series, String label, 
									Boolean isTip, Boolean shouldDestroyView,
									Boolean useExistingView, Boolean cacheAdeEnv) {
		this.viewName = view;
		this.series = series;
		this.isTip = isTip;
		this.label = label;
		this.isUsingLabel = labelExists(this.label);
		this.shouldDestroyView = shouldDestroyView;
		this.useExistingView = useExistingView;
		this.environmentCache = new AdeEnvironmentCache(cacheAdeEnv);
	}
	
	public Boolean getUseExistingView() {
		return useExistingView;
	}

	public Boolean getIsTip() {
		if (this.isTip==null) {
			return false;
		}
		return this.isTip;
	}
	
	public Boolean getShouldDestroyView() {
		if (this.shouldDestroyView==null) {
			return true;
		}
		return this.shouldDestroyView;
	}
	
	public String getSeries() {
		return this.series;
	}
	
	public String getLabel() {
		return this.label;
	}
	
	public String getView() {
		return this.viewName;
	}
	
	@SuppressWarnings("rawtypes")
	protected String getViewName(AbstractBuild build) {
		if(useExistingView){
			return this.viewName;
		} else {
			return this.viewName+"_"+build.getNumber();
		}
	}
	
	public Boolean isUsingLabel() {
		return this.isUsingLabel;
	}
 	
	public Boolean getCacheAdeEnv() {
		return this.environmentCache.isActive();
	}
	/**
	 * this method is called every time a build step runs and allows us to decide how to
	 * wrap any call that needs to run in an ADE view.  
	 * 
	 * the launcher passed in to the setup method is _also_ decorated.  This is important 
	 * because anything that runs in the setup method will also be decorated and in ADE, it's 
	 * important that out-of-view operations be skipped.  We have put this logic in the EnvironmentImpl
	 * class but it may be better to refactor this into the decorateLauncher method where it's more obvious
	 * 
	 * Since it may be a waste of time to enter a view if you already know the environment that
	 * you should use, we may try to cache the environment and continue to use the default launcher
	 * Otherwise, we'll decorate our launcher with the ade useview functionality 
	 */
	@SuppressWarnings("rawtypes")
	@Override
	public Launcher decorateLauncher(AbstractBuild build, Launcher launcher,
			BuildListener listener) throws IOException, InterruptedException,
			RunnerAbortedException {
		if (environmentCache.isActive()) {
			return launcher;
		} else {
			listener.getLogger().println("time to decorate");
			return new UseViewLauncher(launcher,
					new String[]{"ade","useview",getViewName(build),"-exec"});
			
		}
	}
	
	/**
	 * there is a setup phase for all job steps that run within a build wrapper.  This is called
	 * once per job.  For ADE, we use this phase to setup the view (and possibly cache the
	 * environmnt)
	 * 
	 * @return Environment Objects represent the environment that all subsequent Launchers should run in
	 */
	@SuppressWarnings("rawtypes")
	@Override
	public Environment setUp(AbstractBuild build, Launcher launcher,
			BuildListener listener) throws IOException, InterruptedException {
		if (!useExistingView){
			createNewView(build, launcher, listener);
		}

		// if the ADE environment should be cached, grab all the environment variables
		// and cache them in the Environment that will be passed in to each Launcher
		if (environmentCache.isActive()) {
			return environmentCache.createEnvironment(build, launcher, listener, this);
		} else {
			listener.getLogger().println("setup called: use existing view" + getViewName(build));
			return new EnvironmentImpl(launcher,build); 
		}
	}

	@SuppressWarnings("rawtypes")
	private void createNewView(AbstractBuild build, Launcher launcher,
			BuildListener listener) throws IOException, InterruptedException {
		listener.getLogger().println("setup called:  ade createview");
		
 		ProcStarter procStarter = launcher.launch()
				.cmds(
					chooseCreateViewCommand(build, launcher, listener))
				.stdout(listener)
				.stderr(listener.getLogger())
				.envs(getEnvOverrides());

 		Proc proc = launcher.launch(procStarter);
		int exitCode = proc.join();


		if (exitCode!=0) {
			listener.getLogger().println("createview(success):  "+exitCode);
			//return new EnvironmentImpl(launcher,build);
			launcher.kill(getEnvOverrides());
		} else {
			listener.getLogger().println("createview:  "+exitCode);
			//return new EnvironmentImpl(launcher,build);
		}
	}

	/*
	 * there are 3 different ways that we might choose to create the view
	 * 1.  go to the tip (ER from Mike Gilbode)
	 * 2.  accept a possibly parameterized label as input from the job context
	 * 3.  the latest public label
	 */
	@SuppressWarnings("rawtypes")
	private String[] chooseCreateViewCommand(AbstractBuild build,
			Launcher launcher, BuildListener listener) throws IOException,
			InterruptedException {
		if (getIsTip()) {
			return new String[] {
				"ade",
				"createview",
				"-force",
				"-latest",
				"-series",
				getSeries(),
				"-tip_default",
				getViewName(build)};
		} else {
			if (labelExists(this.label)) {
				return new String [] {
					"ade",
					"createview",
					"-force",
					"-label",
					getExpandedLabel(build,listener),
					getViewName(build)
				};
			} else {
				return (new LatestPublicLabelStrategy()).getCommand(build, launcher, listener, this);
			}
		}
	}
	
	String getUser() {
		return ((DescriptorImpl)this.getDescriptor()).getUser();
	}

	String getWorkspace() {
		return ((DescriptorImpl)this.getDescriptor()).getWorkspace();
	}

	String getViewStorage() {
		return ((DescriptorImpl)this.getDescriptor()).getViewStorage();
	}
	
	String getSite() {
		return ((DescriptorImpl)this.getDescriptor()).getSite();
	}

	private String getExpandedLabel(@SuppressWarnings("rawtypes") AbstractBuild build, TaskListener listener) {
		try {
			return build.getEnvironment(listener).expand(this.label);
		} catch (IOException e) {
			listener.error("IOException while trying to expand "+this.label);
			e.printStackTrace();
		} catch (InterruptedException e) {
			listener.error("InterruptedException while trying to expand "+this.label);
			e.printStackTrace();
		}
		return this.label;
	}

	private boolean labelExists(String label) {
		return (label!=null && !"".equals(label));
	}

	Map<String, String> getEnvOverrides(String[] keyValuePairs,TaskListener listener) {
		Map<String,String> map = getEnvOverrides();
        if (keyValuePairs!=null) {
	        for( String keyValue: keyValuePairs ) {
	        	String[] split = keyValue.split("=");
	        	if (split.length<2) {
	        		listener.getLogger().println(keyValue+" not in the correct format");
	        	} else {
	        		map.put(split[0],split[1]);
	        	}
	        }
        }
		return map;
	}
		
	/**
	 * ADE magic that we will need to expose as plugin-level config since all 3 of these settings depend on how
	 * the slave is configured
	 * 
	 * Env overrides do not replace the base environment but augment.  In the case of the "PATH+XYZ" syntax, you can actually
	 * prepend additional entries to PATH environment variables.  This is a special syntax that is specific to Hudson.
	 * 
	 * @return
	 */
	Map<String, String> getEnvOverrides() {
		Map<String,String> overrides = new HashMap<String,String>();
		overrides.put("ADE_SITE",getSite());
		overrides.put("ADE_DEFAULT_VIEW_STORAGE_LOC",getViewStorage());
		overrides.put("ADE_USER",getUser());
		// this is a special syntax that Hudson employs to allow us to prepend entries to the base PATH in 
		// an OS-specific manner
		overrides.put("PATH+INTG","/usr/local/packages/intg/bin");
		overrides.put(UIPBuilder.seriesName,getSeries());
		return overrides;
	}
	
	/**
	 * The UseViewLauncher permits an ADE BuildWrapper to translate all commands to run
	 * within an ADE view.
	 * 
	 * It knows to delegate to the outer launcher during createview/destroyview/showlabels/useview ops
	 * 
	 * @author slim
	 *
	 */
	class UseViewLauncher extends Launcher {
		private Launcher outer;
		private String[] prefix;
		UseViewLauncher(Launcher outer, String[] prefix) {
			super(outer);
			this.outer = outer;
			this.prefix = prefix;
		}
        @Override
        public Proc launch(ProcStarter starter) throws IOException {
        	// don't prefix either createview or destroyview
        	String[] args = starter.cmds().toArray(new String[]{});
        	starter.envs(getEnvOverrides(starter.envs(),listener));
        	if (args.length>1 && (args[1].equals("createview")||args[1].equals("destroyview")||
        			args[1].equals("showlabels")||args[1].equals("useview"))) {
        		listener.getLogger().println("detected createview/destroyview/showlabels");
        		return outer.launch(starter);
        	}
        	// prefix everything else
        	starter.cmds(prefix(starter.cmds().toArray(new String[]{})));
            if (starter.masks() != null) {
                starter.masks(prefix(starter.masks()));
            }
            return outer.launch(starter);
        }

        @Override
        public Channel launchChannel(String[] cmd, OutputStream out, FilePath workDir, Map<String, String> envVars) throws IOException, InterruptedException {
        	if (cmd.length>1 && (cmd[1].equals("createview")||cmd[1].equals("destroyview")||cmd[1].equals("showlabels"))) {
        		listener.getLogger().println("detected createview/destroyview in Channel");
        		return outer.launchChannel(prefix(cmd),out,workDir,envVars);
        	}
            return outer.launchChannel(prefix(cmd),out,workDir,envVars);
        }

        @Override
        public void kill(Map<String, String> modelEnvVars) throws IOException, InterruptedException {
            outer.kill(modelEnvVars);
        }

        private String[] prefix(String[] args) {
            //String[] newArgs = new String[args.length+prefix.length];
            String[] newArgs = new String[prefix.length+1];
            // copy prefix args into the front of the target array
            System.arraycopy(prefix,0,newArgs,0,prefix.length);
            // copy a single space-delimited String to tail of the new arg list
            System.arraycopy(new String[]{spaceDelimitedStringArg(args)},0,newArgs,prefix.length,1);
            return newArgs;
        }
        
        private String spaceDelimitedStringArg(String[] args) {
        	StringBuffer buffer = new StringBuffer();
        	String prefix = "";
        	for (String arg: args) {
        		// String replaced = Util.replaceMacro(arg, replace);
        		buffer.append(prefix+arg);
        		prefix = " ";
        	}
        	return buffer.toString();
        }

        /*
         * fyi:  masks are for "mask"ing out certain args that could contain sensitive information
         * we don't use this in ADE
         */
        private boolean[] prefix(boolean[] args) {
            boolean[] newArgs = new boolean[args.length+prefix.length];
            System.arraycopy(args,0,newArgs,prefix.length,args.length);
            return newArgs;
        }
		
	}

	/**
	 * This BuildWrapper always augments the environment with enough information to use ADE
	 * 
	 * It also registers a tearDown event handler to destroy the view if the view is
	 * not configured to be saved after job completion
	 * 
	 * @author slim
	 *
	 */
	@SuppressWarnings("rawtypes")
	class EnvironmentImpl extends Environment {
		private Launcher launcher;
		private AbstractBuild build;
		private Map<String,String> envMapToAdd = null;
		EnvironmentImpl(Launcher launcher, AbstractBuild build) {
			this.launcher = launcher;
			this.build = build;
		}
		public void setEnvMapToAdd(Map<String, String> envMapToAdd) {
			this.envMapToAdd = envMapToAdd;
		}
		@Override
		public void buildEnvVars(Map<String, String> env) {
			String user = System.getProperty("user.name");
			env.put(UIPBuilder.seriesName,series);
			env.put("ADE_USER",getUser());
			env.put("VIEW_NAME",getViewName(build));
			env.put("ADE_VIEW_ROOT",build.getWorkspace()+"/"+getUser()+"_"+getViewName(build));
			if (envMapToAdd != null ){
				env.putAll(envMapToAdd);
			}
		}
		@Override
		public boolean tearDown(AbstractBuild build, BuildListener listener)
				throws IOException, InterruptedException {
			try {
				if (getShouldDestroyView()) {
					listener.getLogger().println("tearing down:  ade destroyview");
					ProcStarter procStarter = launcher.launch()
						.cmds(new String[] {
							"ade",
							"destroyview",
							getViewName(build),
							"-force"})
						.stdout(listener)
						.stderr(listener.getLogger())
						.envs(getEnvOverrides());
					Proc proc = launcher.launch(procStarter);
					int exitCode = proc.join();
					listener.getLogger().println("destroyview:  "+exitCode);
				} else {
					listener.getLogger().println("saving view");
				}
			} catch (Exception e) {
				listener.getLogger().println("Error destroying view:  "+e.getMessage());
				return false;
			}
			return true;
		}
	}

	@Extension
	public static class DescriptorImpl extends BuildWrapperDescriptor {
		private String user;
		private String workspace;
		private String viewStorage;
		private String site;
		
		public DescriptorImpl() {
			load();
		}
		
		@Override
		public boolean isApplicable(AbstractProject<?, ?> arg0) {
			return true;
		}

		@Override
		public String getDisplayName() {
			return "ADEBuildWrapper";
		}
		
		public String getSite() {
			return this.site;
		}
		
		public void setSite(String s) {
			this.site = s;
		}
		
		public String getUser() {
			return this.user;
		}
		
		public void setUser(String user) {
			this.user = user;
		}
		
		public String getWorkspace() {
			return this.workspace;
		}
		
		public void setWorkspace(String workspace) {
			this.workspace = workspace;
		}
		
		public String getViewStorage() {
			return this.viewStorage;
		}
		
		public void setViewStorage(String v) {
			this.viewStorage = v;
		}
		
		@Override
		public boolean configure(StaplerRequest req)
				throws hudson.model.Descriptor.FormException {
			this.user = req.getParameter("ade_classic.user");
			this.workspace = req.getParameter("ade_classic.workspace");
			this.viewStorage = req.getParameter("ade_classic.view_storage");
			this.site = req.getParameter("ade_classic.site");
			save();
			return super.configure(req);
		}
	}
}
