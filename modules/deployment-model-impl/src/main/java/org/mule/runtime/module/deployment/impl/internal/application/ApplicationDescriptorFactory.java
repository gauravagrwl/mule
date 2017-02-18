/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.deployment.impl.internal.application;

import static java.io.File.separator;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static org.apache.commons.io.FileUtils.listFiles;
import static org.apache.commons.io.IOCase.INSENSITIVE;
import static org.apache.commons.lang.SystemUtils.LINE_SEPARATOR;
import static org.mule.runtime.api.util.Preconditions.checkArgument;
import static org.mule.runtime.container.api.MuleFoldersUtil.PLUGINS_FOLDER;
import static org.mule.runtime.core.config.bootstrap.ArtifactType.APP;
import static org.mule.runtime.deployment.model.api.application.ApplicationDescriptor.DEFAULT_APP_PROPERTIES_RESOURCE;
import static org.mule.runtime.deployment.model.api.plugin.ArtifactPluginDescriptor.MULE_ARTIFACT_FOLDER;
import static org.mule.runtime.module.artifact.descriptor.BundleScope.COMPILE;
import static org.mule.runtime.module.deployment.impl.internal.artifact.ArtifactFactoryUtils.getDeploymentFile;
import org.mule.runtime.api.deployment.meta.MuleArtifactLoaderDescriptor;
import org.mule.runtime.api.deployment.meta.MulePluginModel;
import org.mule.runtime.api.deployment.persistence.MulePluginModelJsonSerializer;
import org.mule.runtime.container.api.MuleFoldersUtil;
import org.mule.runtime.core.registry.SpiServiceRegistry;
import org.mule.runtime.core.util.PropertiesUtils;
import org.mule.runtime.deployment.model.api.application.ApplicationDescriptor;
import org.mule.runtime.deployment.model.api.plugin.ArtifactPluginDescriptor;
import org.mule.runtime.deployment.model.api.plugin.ArtifactPluginRepository;
import org.mule.runtime.module.artifact.descriptor.ArtifactDescriptorCreateException;
import org.mule.runtime.module.artifact.descriptor.ArtifactDescriptorFactory;
import org.mule.runtime.module.artifact.descriptor.BundleDependency;
import org.mule.runtime.module.artifact.descriptor.BundleDescriptor;
import org.mule.runtime.module.artifact.descriptor.BundleDescriptorLoader;
import org.mule.runtime.module.artifact.descriptor.ClassLoaderModel;
import org.mule.runtime.module.artifact.descriptor.ClassLoaderModelLoader;
import org.mule.runtime.module.artifact.descriptor.InvalidDescriptorLoaderException;
import org.mule.runtime.module.artifact.util.FileJarExplorer;
import org.mule.runtime.module.artifact.util.JarExplorer;
import org.mule.runtime.module.artifact.util.JarInfo;
import org.mule.runtime.module.deployment.impl.internal.artifact.DescriptorLoaderRepository;
import org.mule.runtime.module.deployment.impl.internal.artifact.LoaderNotFoundException;
import org.mule.runtime.module.deployment.impl.internal.artifact.ServiceRegistryDescriptorLoaderRepository;
import org.mule.runtime.module.deployment.impl.internal.plugin.ArtifactPluginDescriptorLoader;
import org.mule.runtime.module.reboot.MuleContainerBootstrapUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.SuffixFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates artifact descriptor for application
 */
public class ApplicationDescriptorFactory implements ArtifactDescriptorFactory<ApplicationDescriptor> {

  public static final String SYSTEM_PROPERTY_OVERRIDE = "-O";
  private static final String UNKNOWN = "unknown";

  private static final Logger logger = LoggerFactory.getLogger(ApplicationDescriptorFactory.class);
  private static final String MULE_APPLICATION_JSON = "mule-app.json";

  private final ArtifactPluginRepository applicationPluginRepository;
  private final ArtifactPluginDescriptorLoader artifactPluginDescriptorLoader;

  private final DescriptorLoaderRepository descriptorLoaderRepository;


  public ApplicationDescriptorFactory(ArtifactPluginDescriptorLoader artifactPluginDescriptorLoader,
                                      ArtifactPluginRepository applicationPluginRepository) {
    checkArgument(artifactPluginDescriptorLoader != null, "ApplicationPluginDescriptorFactory cannot be null");
    checkArgument(applicationPluginRepository != null, "ApplicationPluginRepository cannot be null");
    this.applicationPluginRepository = applicationPluginRepository;
    this.artifactPluginDescriptorLoader = artifactPluginDescriptorLoader;
    descriptorLoaderRepository = new ServiceRegistryDescriptorLoaderRepository(new SpiServiceRegistry());
  }

  public ApplicationDescriptor create(File artifactFolder) throws ArtifactDescriptorCreateException {
    ApplicationDescriptor applicationDescriptor;
    final File mulePluginJsonFile = new File(artifactFolder, MULE_ARTIFACT_FOLDER + separator + MULE_APPLICATION_JSON);
    if (mulePluginJsonFile.exists()) {
      applicationDescriptor = loadFromJsonDescriptor(artifactFolder, mulePluginJsonFile);
    } else {
      applicationDescriptor = createFromProperties(artifactFolder);
    }

    return applicationDescriptor;
  }


  protected static String invalidClassLoaderModelIdError(File pluginFolder,
                                                         MuleArtifactLoaderDescriptor classLoaderModelLoaderDescriptor) {
    return format("The identifier '%s' for a class loader model descriptor is not supported (error found while reading plugin '%s')",
                  classLoaderModelLoaderDescriptor.getId(),
                  pluginFolder.getAbsolutePath());
  }


  private BundleDescriptor getBundleDescriptor(File pluginFolder, MulePluginModel mulePluginModel) {
    BundleDescriptorLoader bundleDescriptorLoader;
    try {
      bundleDescriptorLoader =
          descriptorLoaderRepository.get(mulePluginModel.getBundleDescriptorLoader().getId(), APP, BundleDescriptorLoader.class);
    } catch (LoaderNotFoundException e) {
      throw new ArtifactDescriptorCreateException(invalidBundleDescriptorLoaderIdError(pluginFolder, mulePluginModel
          .getBundleDescriptorLoader()));
    }

    try {
      return bundleDescriptorLoader.load(pluginFolder, mulePluginModel.getBundleDescriptorLoader().getAttributes());
    } catch (InvalidDescriptorLoaderException e) {
      throw new ArtifactDescriptorCreateException(e);
    }
  }


  protected static String invalidBundleDescriptorLoaderIdError(File pluginFolder,
                                                               MuleArtifactLoaderDescriptor bundleDescriptorLoader) {
    return format("The identifier '%s' for a bundle descriptor loader is not supported (error found while reading plugin '%s')",
                  bundleDescriptorLoader.getId(),
                  pluginFolder.getAbsolutePath());
  }


  private ApplicationDescriptor loadFromJsonDescriptor(File applicationFolder, File mulePluginJsonFile) {
    final MulePluginModel mulePluginModel = getMulePluginJsonDescriber(mulePluginJsonFile);

    final ApplicationDescriptor descriptor = new ApplicationDescriptor(mulePluginModel.getName());
    descriptor.setArtifactLocation(applicationFolder);
    descriptor.setRootFolder(applicationFolder);

    mulePluginModel.getClassLoaderModelLoaderDescriptor().ifPresent(classLoaderModelLoaderDescriptor -> {
      ClassLoaderModel classLoaderModel = getClassLoaderModel(applicationFolder, classLoaderModelLoaderDescriptor);
      descriptor.setClassLoaderModel(classLoaderModel);

      descriptor.setClassLoaderModel(classLoaderModel);

      try {
        descriptor.setPlugins(createArtifactPluginDescriptors(classLoaderModel, applicationFolder.getName()));
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    });

    descriptor.setBundleDescriptor(getBundleDescriptor(applicationFolder, mulePluginModel));

    return descriptor;
  }

  private MulePluginModel getMulePluginJsonDescriber(File jsonFile) {
    try (InputStream stream = new FileInputStream(jsonFile)) {
      return new MulePluginModelJsonSerializer().deserialize(IOUtils.toString(stream));
    } catch (IOException e) {
      throw new IllegalArgumentException(format("Could not read extension describer on plugin '%s'", jsonFile.getAbsolutePath()),
                                         e);
    }
  }

  private ClassLoaderModel getClassLoaderModel(File pluginFolder, MuleArtifactLoaderDescriptor classLoaderModelLoaderDescriptor) {
    ClassLoaderModelLoader classLoaderModelLoader;
    try {
      classLoaderModelLoader =
          descriptorLoaderRepository.get(classLoaderModelLoaderDescriptor.getId(), APP, ClassLoaderModelLoader.class);
    } catch (LoaderNotFoundException e) {
      throw new ArtifactDescriptorCreateException(invalidClassLoaderModelIdError(pluginFolder,
                                                                                 classLoaderModelLoaderDescriptor));
    }

    final ClassLoaderModel classLoaderModel;
    try {
      classLoaderModel = classLoaderModelLoader.load(pluginFolder, classLoaderModelLoaderDescriptor.getAttributes());
    } catch (InvalidDescriptorLoaderException e) {
      throw new ArtifactDescriptorCreateException(e);
    }
    return classLoaderModel;
  }

  public ApplicationDescriptor createFromProperties(File artifactFolder) throws ArtifactDescriptorCreateException {
    if (!artifactFolder.exists()) {
      throw new IllegalArgumentException(format("Application directory does not exist: '%s'", artifactFolder));
    }

    final String appName = artifactFolder.getName();
    ApplicationDescriptor desc;

    try {
      final File deployPropertiesFile = getDeploymentFile(artifactFolder);
      if (deployPropertiesFile != null) {
        // lookup the implementation by extension
        final PropertiesDescriptorParser descriptorParser = new PropertiesDescriptorParser();
        desc = descriptorParser.parse(artifactFolder, deployPropertiesFile, appName);
      } else {
        desc = new EmptyApplicationDescriptor(artifactFolder);
      }

      // get a ref to an optional app props file (right next to the descriptor)
      final File appPropsFile = new File(artifactFolder, DEFAULT_APP_PROPERTIES_RESOURCE);
      setApplicationProperties(desc, appPropsFile);

      File appClassesFolder = getAppClassesFolder(desc);
      URL[] libraries = findLibraries(desc);
      URL[] sharedLibraries = findSharedLibraries(desc);

      List<URL> urls = getApplicationResourceUrls(appClassesFolder.toURI().toURL(), libraries, sharedLibraries);
      if (!urls.isEmpty() && logger.isInfoEnabled()) {
        logArtifactRuntimeUrls(appName, urls);
      }

      ClassLoaderModel.ClassLoaderModelBuilder classLoaderModelBuilder = new ClassLoaderModel.ClassLoaderModelBuilder();
      for (URL url : urls) {
        classLoaderModelBuilder.containing(url);
      }
      JarInfo jarInfo = findApplicationResources(desc, sharedLibraries);
      classLoaderModelBuilder.exportingPackages(jarInfo.getPackages())
          .exportingResources(jarInfo.getResources());

      Set<BundleDependency> plugins = getPluginDependencies(artifactFolder);
      classLoaderModelBuilder.dependingOn(plugins);
      ClassLoaderModel classLoaderModel = classLoaderModelBuilder.build();
      desc.setClassLoaderModel(classLoaderModel);


      desc.setPlugins(createArtifactPluginDescriptors(classLoaderModel, artifactFolder.getName()));
    } catch (IOException e) {
      throw new ArtifactDescriptorCreateException("Unable to create application descriptor", e);
    }

    return desc;
  }

  private Set<BundleDependency> getPluginDependencies(File applicationFolder) throws MalformedURLException {
    File pluginsFolders = new File(applicationFolder, "plugins");
    File[] files = pluginsFolders.listFiles();
    Set<BundleDependency> plugins = new HashSet<>();
    if (!pluginsFolders.exists()) {
      return plugins;
    }
    for (File file : files) {
      plugins.add(new BundleDependency.Builder().setBundleUrl(file.toURL()).setScope(COMPILE)
          .setDescriptor(new BundleDescriptor.Builder().setArtifactId(UNKNOWN).setGroupId(UNKNOWN).setVersion(UNKNOWN)
              .setClassifier("mule-plugin").build())
          .build());
    }
    return plugins;
  }

  private Set<ArtifactPluginDescriptor> createArtifactPluginDescriptors(ClassLoaderModel classLoaderModel, String applicationName)
      throws IOException {
    Set<ArtifactPluginDescriptor> pluginDescriptors = new HashSet<>();
    for (BundleDependency bundleDependency : classLoaderModel.getDependencies()) {
      if (bundleDependency.getDescriptor().getClassifier().get().equals("mule-plugin")) {
        // TODO(pablo.kraan): embedded - get the file form the app descriptor
        File pluginFile = new File(bundleDependency.getBundleUrl().getFile());
        File tempFolder = MuleFoldersUtil.getAppTempFolder(applicationName);
        tempFolder.delete();
        tempFolder.mkdir();
        pluginDescriptors.add(artifactPluginDescriptorLoader.load(pluginFile, tempFolder));
        tempFolder.delete();
      }
    }
    return pluginDescriptors;
  }

  private URL[] findLibraries(ApplicationDescriptor descriptor) throws MalformedURLException {
    return findJars(getAppLibFolder(descriptor)).toArray(new URL[0]);
  }

  protected File getAppLibFolder(ApplicationDescriptor descriptor) {
    return MuleFoldersUtil.getAppLibFolder(descriptor.getName());
  }

  private URL[] findSharedLibraries(ApplicationDescriptor descriptor) throws MalformedURLException {
    return findJars(getAppSharedLibsFolder(descriptor)).toArray(new URL[0]);
  }

  protected File getAppSharedLibsFolder(ApplicationDescriptor descriptor) {
    return MuleFoldersUtil.getAppSharedLibsFolder(descriptor.getName());
  }

  private JarInfo findApplicationResources(ApplicationDescriptor descriptor, URL[] sharedLibraries) {
    final JarInfo librariesInfo = findExportedResources(sharedLibraries);
    final JarInfo classesInfo;
    try {
      final File appClassesFolder = getAppClassesFolder(descriptor);
      if (appClassesFolder.exists()) {
        classesInfo = findExportedResources(appClassesFolder.toURI().toURL());
      } else {
        classesInfo = new JarInfo(emptySet(), emptySet());
      }
    } catch (MalformedURLException e) {
      throw new RuntimeException("Cannot read application classes folder", e);
    }
    librariesInfo.getPackages().addAll(classesInfo.getPackages());
    librariesInfo.getResources().addAll(classesInfo.getResources());

    return librariesInfo;
  }

  protected File getAppClassesFolder(ApplicationDescriptor descriptor) {
    return MuleFoldersUtil.getAppClassesFolder(descriptor.getName());
  }

  private JarInfo findExportedResources(URL... libraries) {
    Set<String> packages = new HashSet<>();
    Set<String> resources = new HashSet<>();
    final JarExplorer jarExplorer = new FileJarExplorer();

    for (URL library : libraries) {
      final JarInfo jarInfo = jarExplorer.explore(library);
      packages.addAll(jarInfo.getPackages());
      resources.addAll(jarInfo.getResources());
    }

    return new JarInfo(packages, resources);
  }

  protected List<URL> findJars(File dir) throws MalformedURLException {
    List<URL> result = new LinkedList<>();

    if (dir.exists() && dir.canRead()) {
      @SuppressWarnings("unchecked")
      Collection<File> jars = listFiles(dir, new String[] {"jar"}, false);

      for (File jar : jars) {
        result.add(jar.toURI().toURL());
      }
    }

    return result;
  }

  private List<ArtifactPluginDescriptor> getAllApplicationPlugins(Set<ArtifactPluginDescriptor> plugins) {
    final List<ArtifactPluginDescriptor> result =
        new LinkedList<>(applicationPluginRepository.getContainerArtifactPluginDescriptors());
    result.addAll(plugins);

    // Sorts plugins by name to ensure consistent deployment
    result.sort(new Comparator<ArtifactPluginDescriptor>() {

      @Override
      public int compare(ArtifactPluginDescriptor descriptor1, ArtifactPluginDescriptor descriptor2) {
        return descriptor1.getName().compareTo(descriptor2.getName());
      }
    });

    return result;
  }

  private Set<ArtifactPluginDescriptor> parsePluginDescriptors(File appDir, ApplicationDescriptor appDescriptor)
      throws IOException {
    final File pluginsDir = new File(appDir, PLUGINS_FOLDER);
    // TODO(fernandezlautaro): MULE-11383 all artifacts must be .jar files
    String[] pluginZips = pluginsDir.list(new SuffixFileFilter(asList(".zip", ".jar"), INSENSITIVE));
    if (pluginZips == null || pluginZips.length == 0) {
      return emptySet();
    }

    Arrays.sort(pluginZips);
    Set<ArtifactPluginDescriptor> pds = new HashSet<>(pluginZips.length);

    for (String pluginZip : pluginZips) {
      String unpackDestinationFolder = appDescriptor.getName() + separator + PLUGINS_FOLDER + separator;
      File pluginZipFile = new File(pluginsDir, pluginZip);
      pds.add(artifactPluginDescriptorLoader
          .load(pluginZipFile, new File(MuleContainerBootstrapUtils.getMuleTmpDir(), unpackDestinationFolder)));
    }
    return pds;
  }

  public void setApplicationProperties(ApplicationDescriptor desc, File appPropsFile) {
    // ugh, no straightforward way to convert a HashTable to a map
    Map<String, String> m = new HashMap<>();

    if (appPropsFile.exists() && appPropsFile.canRead()) {
      final Properties props;
      try {
        props = PropertiesUtils.loadProperties(appPropsFile.toURI().toURL());
      } catch (IOException e) {
        throw new IllegalArgumentException("Unable to obtain application properties file URL", e);
      }
      for (Object key : props.keySet()) {
        m.put(key.toString(), props.getProperty(key.toString()));
      }
    }

    // Override with any system properties prepended with "-O" for ("override"))
    Properties sysProps = System.getProperties();
    for (Map.Entry<Object, Object> entry : sysProps.entrySet()) {
      String key = entry.getKey().toString();
      if (key.startsWith(SYSTEM_PROPERTY_OVERRIDE)) {
        m.put(key.substring(SYSTEM_PROPERTY_OVERRIDE.length()), entry.getValue().toString());
      }
    }
    desc.setAppProperties(m);
  }


  private List<URL> getApplicationResourceUrls(URL classesFolderUrl, URL[] libraries, URL[] sharedLibraries) {
    List<URL> urls = new LinkedList<>();
    urls.add(classesFolderUrl);

    for (URL url : libraries) {
      urls.add(url);
    }

    for (URL url : sharedLibraries) {
      urls.add(url);
    }

    return urls;
  }

  private void logArtifactRuntimeUrls(String appName, List<URL> urls) {
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("[%s] Loading the following jars:%n", appName));
    sb.append("=============================").append(LINE_SEPARATOR);

    for (URL url : urls) {
      sb.append(url).append(LINE_SEPARATOR);
    }

    sb.append("=============================").append(LINE_SEPARATOR);
    logger.info(sb.toString());
  }
}
