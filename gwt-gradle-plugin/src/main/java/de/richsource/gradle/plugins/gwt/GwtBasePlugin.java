/**
 * Copyright (C) 2013 Steffen Schaefer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.richsource.gradle.plugins.gwt;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.IConventionAware;
import org.gradle.api.internal.artifacts.dependencies.DefaultExternalModuleDependency;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.testing.Test;

public class GwtBasePlugin implements Plugin<Project> {
	public static final String GWT_CONFIGURATION = "gwt";
	public static final String GWT_SDK_CONFIGURATION = "gwtSdk";
	public static final String EXTENSION_NAME = "gwt";
	public static final String BUILD_DIR = "gwt";
	public static final String OUT_DIR = "out";
	public static final String DRAFT_OUT_DIR = "draftOut";
	public static final String EXTRA_DIR = "extra";
	public static final String WORK_DIR = "work";
	public static final String GEN_DIR = "gen";
	public static final String CACHE_DIR = "cache";
	public static final String LOG_DIR = "log";
	
	public static final String DEV_WAR = "war";
	
	public static final String TASK_COMPILE_GWT = "compileGwt";
	public static final String TASK_DRAFT_COMPILE_GWT = "draftCompileGwt";
	public static final String TASK_GWT_SUPER_DEV = "gwtSuperDev";
	
	public static final String GWT_GROUP = "com.google.gwt";
	public static final String GWT_DEV = "gwt-dev";
	public static final String GWT_USER = "gwt-user";
	public static final String GWT_CODESERVER = "gwt-codeserver";
	public static final String GWT_ELEMENTAL = "gwt-elemental";
	public static final String GWT_SERVLET = "gwt-servlet";
	
	private static final Logger logger = Logging.getLogger(GwtBasePlugin.class);
	private Project project;
	private GwtPluginExtension extension;
	private Configuration gwtConfiguration;
	private Configuration gwtSdkConfiguration;

	@Override
	public void apply(final Project project) {
		this.project = project;
		project.getPlugins().apply(JavaPlugin.class);
		
		final File gwtBuildDir = new File(project.getBuildDir(), BUILD_DIR);
		
		extension = configureGwtExtension(gwtBuildDir);
		
		configureAbstractActionTasks();
		configureAbstractTasks();
		configureGwtCompile();
		configureGwtDev();
		configureGwtSuperDev();
		
		gwtConfiguration = project.getConfigurations().create(GWT_CONFIGURATION);
		gwtSdkConfiguration = project.getConfigurations().create(GWT_SDK_CONFIGURATION);
		final ConfigurableFileCollection allGwtConfigurations = project.files(gwtConfiguration, gwtSdkConfiguration);
		
		addToMainSourceSetClasspath(allGwtConfigurations);
		
		final GwtCompile compileTask = project.getTasks().create(TASK_COMPILE_GWT, GwtCompile.class);
		compileTask.setWar(new File(gwtBuildDir, OUT_DIR));
		compileTask.setDescription("Runs the GWT compiler to translate Java sources to JavaScript for production ready output");
		
		final GwtDraftCompile draftCompileTask = project.getTasks().create(TASK_DRAFT_COMPILE_GWT, GwtDraftCompile.class);
		draftCompileTask.setWar(new File(gwtBuildDir, DRAFT_OUT_DIR));
		draftCompileTask.setDescription("Runs the GWT compiler to produce draft quality output used for development");
		
		project.afterEvaluate(new Action<Project>() {
			@Override
			public void execute(final Project project) {
				boolean versionSet = false;
				int major = 2;
				int minor = 5;
				
				final String gwtVersion = extension.getGwtVersion();
				if(gwtVersion != null && !extension.getGwtVersion().isEmpty()) {
					final String[] token = gwtVersion.split("\\.");
					if(token.length>=2) {
						try {
							major = Integer.parseInt(token[0]);
							minor = Integer.parseInt(token[1]);
							versionSet = true;
						} catch(NumberFormatException e) {
							logger.warn("GWT version "+extension.getGwtVersion()+" can not be parsed. Valid versions must have the format major.minor.patch where major and minor are positive integer numbers.");
						}
					} else {
						logger.warn("GWT version "+extension.getGwtVersion()+" can not be parsed. Valid versions must have the format major.minor.patch where major and minor are positive integer numbers.");
					}
				}
				
				if ((major == 2 && minor >= 5) || major > 2) {
					if(extension.isCodeserver()) {
						createSuperDevModeTask(project);
					}
				}
				
				if(versionSet) {
					project.getDependencies().add(GWT_SDK_CONFIGURATION, new DefaultExternalModuleDependency(GWT_GROUP, GWT_DEV, gwtVersion));
					project.getDependencies().add(GWT_SDK_CONFIGURATION, new DefaultExternalModuleDependency(GWT_GROUP, GWT_USER, gwtVersion));
					project.getDependencies().add(JavaPlugin.RUNTIME_CONFIGURATION_NAME, new DefaultExternalModuleDependency(GWT_GROUP, GWT_SERVLET, gwtVersion));
					
					if ((major == 2 && minor >= 5) || major > 2) {
						if(extension.isCodeserver()) {
							project.getDependencies().add(GWT_CONFIGURATION, new DefaultExternalModuleDependency(GWT_GROUP, GWT_CODESERVER, gwtVersion));
						}
						if(extension.isElemental()) {
							project.getDependencies().add(GWT_CONFIGURATION, new DefaultExternalModuleDependency(GWT_GROUP, GWT_ELEMENTAL, gwtVersion));
						}
					} else {
						logger.warn("GWT version is <2.5 -> additional dependencies are not added.");
					}
				}
				
			}});
		
		final SourceSet testSourceSet = getTestSourceSet();
		testSourceSet.setCompileClasspath(testSourceSet.getCompileClasspath().plus(allGwtConfigurations));
		testSourceSet.setRuntimeClasspath(
				project.files(
						getMainSourceSet().getAllJava().getSrcDirs().toArray())
						.plus(project.files(testSourceSet.getAllJava()
								.getSrcDirs().toArray()))
				.plus(allGwtConfigurations).plus(testSourceSet
				.getRuntimeClasspath()));
		
		project.getTasks().withType(Test.class, new Action<Test>() {
			@Override
			public void execute(final Test testTask) {
				testTask.getTestLogging().setShowStandardStreams(true);
				
				final GwtTestExtension testExtension = testTask.getExtensions().create("gwt", GwtTestExtension.class);
				testExtension.configure(extension, (IConventionAware) testExtension);

				testTask.doFirst(new Action<Task>() {
					@Override
					public void execute(Task arg0) {
						String gwtArgs = testExtension.getParameterString();
						testTask.systemProperty("gwt.args", gwtArgs);
						logger.info("Using gwt.args for test: "+ gwtArgs);

						if (testExtension.getCacheDir() != null) {
							testTask.systemProperty("gwt.persistentunitcachedir", testExtension.getCacheDir());
							testExtension.getCacheDir().mkdirs();
							logger.info("Using gwt.persistentunitcachedir for test: {0}", testExtension.getCacheDir());
						}
					}
				});
			}
		});
	}

	private GwtPluginExtension configureGwtExtension(final File buildDir) {
		final SourceSet mainSourceSet = getMainSourceSet();
		
		final GwtPluginExtension extension = project.getExtensions().create(EXTENSION_NAME, GwtPluginExtension.class);
		extension.setDevWar(project.file(DEV_WAR));
		extension.setExtraDir(new File(buildDir, EXTRA_DIR));
		extension.setWorkDir(new File(buildDir, WORK_DIR));
		extension.setGenDir(new File(buildDir, GEN_DIR));
		extension.setCacheDir(new File(buildDir, CACHE_DIR));
		extension.getDev().setLogDir(new File(buildDir, LOG_DIR));
		extension.getCompiler().setLocalWorkers(Runtime.getRuntime().availableProcessors());
		extension.setSrc(project.files(mainSourceSet.getAllJava().getSrcDirs()).plus(project.files(mainSourceSet.getOutput().getResourcesDir())));
		extension.setLogLevel(getLogLevel());
		return extension;
	}


	private void createSuperDevModeTask(final Project project) {
		final GwtSuperDev superDevTask = project.getTasks().create(TASK_GWT_SUPER_DEV, GwtSuperDev.class);
		superDevTask.dependsOn(JavaPlugin.COMPILE_JAVA_TASK_NAME, JavaPlugin.PROCESS_RESOURCES_TASK_NAME);
		superDevTask.setDescription("Runs the GWT super dev mode");
	}

	private void configureAbstractTasks() {
		project.getTasks().withType(AbstractGwtTask.class, new Action<AbstractGwtTask>() {
			@Override
			public void execute(final AbstractGwtTask task) {
				task.conventionMapping("extra", new Callable<File>(){
					@Override
					public File call() throws Exception {
						return extension.getExtraDir();
					}});
				task.conventionMapping("workDir", new Callable<File>(){
					@Override
					public File call() throws Exception {
						return extension.getWorkDir();
					}});
				task.conventionMapping("gen", new Callable<File>(){
					@Override
					public File call() throws Exception {
						return extension.getGenDir();
					}});
				task.conventionMapping("cacheDir", new Callable<File>(){
					@Override
					public File call() throws Exception {
						return extension.getCacheDir();
					}});
				task.conventionMapping("logLevel", new Callable<LogLevel>(){
					@Override
					public LogLevel call() throws Exception {
						return extension.getLogLevel();
					}});
			}});
	}
	
	private void configureAbstractActionTasks() {
		final JavaPluginConvention javaConvention = getJavaConvention();
		final SourceSet mainSourceSet = javaConvention.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
		project.getTasks().withType(AbstractGwtActionTask.class, new Action<AbstractGwtActionTask>() {
			@Override
			public void execute(final AbstractGwtActionTask task) {
				task.conventionMapping("modules", new Callable<List<String>>() {
					@Override
					public List<String> call() throws Exception {
						final List<String> devModules = extension.getDevModules();
						if(task.isDevTask() && devModules!= null && !devModules.isEmpty()) {
							return devModules;
						}
						return extension.getModules();
					}
				});
				task.conventionMapping("src", new Callable<FileCollection>() {
					@Override
					public FileCollection call() throws Exception {
						return extension.getSrc();
					}
				});
				task.conventionMapping("classpath", new Callable<FileCollection>() {
					@Override
					public FileCollection call() throws Exception {
						return mainSourceSet.getCompileClasspath().plus(project.files(mainSourceSet.getOutput().getClassesDir()));
					}
				});
				task.conventionMapping("minHeapSize", new Callable<String>() {
					@Override
					public String call() throws Exception {
						return extension.getMinHeapSize();
					}
				});
				task.conventionMapping("maxHeapSize", new Callable<String>() {
					@Override
					public String call() throws Exception {
						return extension.getMaxHeapSize();
					}
				});
			}});
	}
	
	private void configureGwtCompile() {
		project.getTasks().withType(AbstractGwtCompile.class, new Action<AbstractGwtCompile>() {
			@Override
			public void execute(final AbstractGwtCompile task) {
				task.configure(extension.getCompiler());
			}
		});
	}

	private void configureGwtDev() {
		final boolean debug = "true".equals(System.getProperty("gwtDev.debug"));
		project.getTasks().withType(GwtDev.class, new Action<GwtDev>() {
			@Override
			public void execute(final GwtDev task) {
				task.configure(extension.getDev());
				task.setDebug(debug);
			}
		});
	}
	
	private void configureGwtSuperDev() {
		project.getTasks().withType(GwtSuperDev.class, new Action<GwtSuperDev>() {
			@Override
			public void execute(final GwtSuperDev task) {
				task.configure(extension.getSuperDev());
				task.conventionMapping("workDir", new Callable<File>() {

					@Override
					public File call() throws Exception {
						return extension.getWorkDir();
					}});
			}
		});
	}
	
	private LogLevel getLogLevel() {
		if(logger.isTraceEnabled()) {
			return LogLevel.TRACE;
		} else if(logger.isDebugEnabled()) {
			return LogLevel.DEBUG;
		} else if(logger.isInfoEnabled()) {
			return LogLevel.INFO;
		} else if(logger.isLifecycleEnabled() || logger.isWarnEnabled()) {
			return LogLevel.WARN;
		}
		// QUIET or ERROR
		return LogLevel.ERROR;
	}
	
	private SourceSet getMainSourceSet() {
		return getJavaConvention().getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
	}
	
	private SourceSet getTestSourceSet() {
		return getJavaConvention().getSourceSets().getByName(SourceSet.TEST_SOURCE_SET_NAME);
	}

	private JavaPluginConvention getJavaConvention() {
		return project.getConvention().getPlugin(JavaPluginConvention.class);
	}
	
	private void addToMainSourceSetClasspath(FileCollection fileCollection) {
		final SourceSet mainSourceSet = getMainSourceSet();
		mainSourceSet.setCompileClasspath(getMainSourceSet().getCompileClasspath().plus(fileCollection));
	}
	
	public GwtPluginExtension getExtension() {
		return extension;
	}
	
	public Configuration getGwtConfiguration() {
		return gwtConfiguration;
	}
	
	public Configuration getGwtSdkConfiguration() {
		return gwtSdkConfiguration;
	}
}