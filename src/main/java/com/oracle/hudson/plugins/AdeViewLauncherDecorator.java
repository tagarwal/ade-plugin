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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
//import hudson.model.Cause.*;

public class AdeViewLauncherDecorator extends BuildWrapper {
	
	private String viewName;
	private String series;
	private String label;
	private Boolean isTip = false;
	private Boolean shouldDestroyView = true;
	private Boolean useExistingView = false;
	private Boolean isUsingLabel = false;
	private Boolean cacheAdeEnv = false;
	
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
		this.cacheAdeEnv = cacheAdeEnv;
	}
	
	private String getUser() {
		return ((DescriptorImpl)this.getDescriptor()).getUser();
	}
	
	private String getWorkspace() {
		return ((DescriptorImpl)this.getDescriptor()).getWorkspace();
	}
	
	private String getViewStorage() {
		return ((DescriptorImpl)this.getDescriptor()).getViewStorage();
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
	
	private String getExpandedLabel(AbstractBuild build, TaskListener listener) {
		try {
			return build.getEnvironment(listener).expand(this.label);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
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
 	
	@SuppressWarnings("rawtypes")
	@Override
	public Launcher decorateLauncher(AbstractBuild build, Launcher launcher,
			BuildListener listener) throws IOException, InterruptedException,
			RunnerAbortedException {
		
		if (cacheAdeEnv) {
			return launcher;
		}
		
		FilePath workspace = build.getWorkspace();
		listener.getLogger().println("time to decorate");
		
		final Launcher outer = launcher;

		final String[] prefix = new String[]{"ade","useview",getViewName(build),"-exec"};
		final BuildListener l = listener;
//		final String viewName = getViewName(build);
		return new Launcher(outer) {
            @Override
            public Proc launch(ProcStarter starter) throws IOException {
            	// don't prefix either createview or destroyview
            	String[] args = starter.cmds().toArray(new String[]{});
            	starter.envs(getEnvOverrides(starter.envs(),listener));
            	if (args.length>1 && (args[1].equals("createview")||args[1].equals("destroyview")||
            			args[1].equals("showlabels")||args[1].equals("useview"))) {
            		l.getLogger().println("detected createview/destroyview/showlabels");
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
            		l.getLogger().println("detected createview/destroyview in Channel");
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
        };
	}
	
	@SuppressWarnings("rawtypes")
	@Override
	public Environment setUp(AbstractBuild build, Launcher launcher,
			BuildListener listener) throws IOException, InterruptedException {
		String workspace = build.getWorkspace().getRemote();
		
		if (!useExistingView){
			listener.getLogger().println("setup called:  ade createview");
			
			//first try to figure out what is the latest label to which we can refresh
			String[] latestLabelsCmds = new String[] {"ade","showlabels","-series",series,"-latest","-public"};

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			
			Proc proc1 = launcher.launch().cmds(latestLabelsCmds).stdout(out).start();

			proc1.join();
			ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
			BufferedReader br = new BufferedReader(new InputStreamReader(in));
			//only interested in the last line
			String latestPublicLabel = null, tmp;
			while ((tmp = br.readLine()) != null){
				latestPublicLabel = tmp;
			}

			listener.getLogger().println("The latest public label is " + latestPublicLabel);

			
			if (!latestPublicLabel.matches(series + "_[0-9]*\\.[0-9]*.*")){
				launcher.kill(getEnvOverrides());
			}

	 
			String[] commands = null;
			if (!getIsTip()) {
				if (labelExists(this.label)) {
					commands = new String [] {
							"ade",
							"createview",
							"-force",
							"-label",
							getExpandedLabel(build,listener),
							getViewName(build)
					};
				} else {
					commands = new String[] {
						"ade",
						"createview",
						"-force",
						"-label",
						latestPublicLabel,
						getViewName(build)};
				}
			} else {
				commands = new String[] {
						"ade",
						"createview",
						"-force",
						"-latest",
						"-tip_default",
						latestPublicLabel,
						getViewName(build)};
			}
			ProcStarter procStarter = launcher.launch().cmds(commands).stdout(listener).stderr(listener.getLogger()).envs(getEnvOverrides());
			Proc proc = launcher.launch(procStarter);
			int exitCode = proc.join();
			if (exitCode!=0) {
				listener.getLogger().println("createview(success):  "+exitCode);
				return new EnvironmentImpl(launcher,build);
			} else {
				listener.getLogger().println("createview:  "+exitCode);
				return new EnvironmentImpl(launcher,build);
			}
		}
		
		if (cacheAdeEnv){
			ProcStarter uvProcStarter = launcher.launch().cmds("ade","useview",getViewName(build),"-exec","printenv >" + workspace + "/adeEnv").stdout(listener).stderr(listener.getLogger()).envs(getEnvOverrides());
			Proc uvProc = launcher.launch(uvProcStarter);
			int exitCode = uvProc.join();
			//now read the env variables into Env and return that for all builds
			Scanner sc = new Scanner(new File(workspace + "/adeEnv")).useDelimiter("=");
			Map<String,String> envMap = new HashMap<String,String>();
			String key,value;
			while (sc.hasNextLine()){
				key =sc.next();
				sc.skip("=");
				value= sc.nextLine();
				envMap.put(key,value);
			}

			sc.close();

			EnvironmentImpl retEnv = new EnvironmentImpl(launcher,build);
			retEnv.setEnvMapToAdd(envMap);

			return retEnv;
		} else {
			listener.getLogger().println("setup called: use existing view" + getViewName(build));
			return new EnvironmentImpl(launcher,build); 
		}
	}
	
	private boolean labelExists(String label) {
		return (label!=null && !"".equals(label));
	}

	private Map<String, String> getEnvOverrides(String[] keyValuePairs,TaskListener listener) {
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
	private Map<String, String> getEnvOverrides() {
		Map<String,String> overrides = new HashMap<String,String>();
		overrides.put("ADE_SITE","ade_slc");
		overrides.put("ADE_DEFAULT_VIEW_STORAGE_LOC",getViewStorage());
		// this is a special syntax that Hudson employs to allow us to prepend entries to the base PATH in 
		// an OS-specific manner
		overrides.put("PATH+INTG","/usr/local/packages/intg/bin");
		overrides.put(UIPBuilder.seriesName,series);
		return overrides;
	}

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
					ProcStarter procStarter = launcher.launch().cmds(new String[] {
						"ade",
						"destroyview",
						getViewName(build),
						"-force"}).stdout(listener).stderr(listener.getLogger()).envs(getEnvOverrides());
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
			save();
			return super.configure(req);
		}
	}
}
