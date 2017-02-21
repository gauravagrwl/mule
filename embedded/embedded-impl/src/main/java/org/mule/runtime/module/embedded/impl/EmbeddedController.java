/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.embedded.impl;

import static java.lang.System.setProperty;
import static org.apache.commons.io.FileUtils.copyFile;
import static org.mule.runtime.container.api.MuleFoldersUtil.getDomainsFolder;
import static org.mule.runtime.container.api.MuleFoldersUtil.getServicesFolder;
import static org.mule.runtime.core.api.config.MuleProperties.MULE_HOME_DIRECTORY_PROPERTY;
import static org.mule.runtime.module.embedded.impl.SerializationUtils.deserialize;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.container.api.MuleFoldersUtil;
import org.mule.runtime.core.util.FileUtils;
import org.mule.runtime.core.util.FilenameUtils;
import org.mule.runtime.deployment.model.api.application.Application;
import org.mule.runtime.deployment.model.api.application.ApplicationDescriptor;
import org.mule.runtime.module.deployment.impl.internal.MuleArtifactResourcesRegistry;
import org.mule.runtime.module.embedded.api.ArtifactInfo;
import org.mule.runtime.module.embedded.api.ContainerInfo;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EmbeddedController {

  private ArtifactInfo artifactInfo;
  private ContainerInfo containerInfo;
  private Application application;

  public EmbeddedController(byte[] serializedContainerInfo, byte[] serializedAppInfo) throws IOException, ClassNotFoundException {
    containerInfo = deserialize(serializedContainerInfo);
    artifactInfo = deserialize(serializedAppInfo);
  }

  public void start() throws IOException, URISyntaxException {
    setUpEnvironment();
    createApplication();

    application.init();
    application.start();
  }

  private void createApplication() throws IOException, URISyntaxException {
    MuleArtifactResourcesRegistry artifactResourcesRegistry = new MuleArtifactResourcesRegistry.Builder().build();
    List<String> configResources = new ArrayList<>();
    for (URI uri : artifactInfo.getConfigs()) {
      configResources.add(uri.toURL().toString());
    }

    File containerFolder = new File(containerInfo.getContainerBaseFolder().getPath());
    File servicesFolder = new File(containerFolder, "services");
    for (URL url : containerInfo.getServices()) {
      File originalFile = new File(url.getFile());
      File destinationFile = new File(servicesFolder, FilenameUtils.getName(url.getFile()));
      copyFile(originalFile, destinationFile);
    }

    try {
      artifactResourcesRegistry.getServiceManager().start();
    } catch (MuleException e) {
      throw new IllegalStateException(e);
    }


    File applicationFolder = new File(MuleFoldersUtil.getAppsFolder(), "app");
    applicationFolder.mkdirs();

    File muleArtifactFolder = new File(applicationFolder, "META-INF/mule-artifact");
    muleArtifactFolder.mkdirs();
    File descriptorFile = new File(muleArtifactFolder, "mule-application.json");
    File pomFile = new File(muleArtifactFolder, "pom.xml");
    copyFile(new File(artifactInfo.getDescriptorFile().getFile()), descriptorFile);
    copyFile(new File(artifactInfo.getPomFile().getFile()), pomFile);

    ApplicationDescriptor applicationDescriptor =
        artifactResourcesRegistry.getApplicationDescriptorFactory().create(applicationFolder);
    applicationDescriptor.setConfigResources(configResources.toArray(new String[0]));
    applicationDescriptor.setAbsoluteResourcePaths(configResources.toArray(new String[0]));

    artifactResourcesRegistry.getDomainFactory().createArtifact(createDefaultDomainDir());

    for (Map.Entry<String, String> applicationPropertiesEntry : artifactInfo.getApplicationProperties().entrySet()) {
      setProperty(applicationPropertiesEntry.getKey(), applicationPropertiesEntry.getValue());
    }

    application = artifactResourcesRegistry.getApplicationFactory().createAppFrom(applicationDescriptor);
  }

  public void stop() {
    FileUtils.deleteTree(new File(containerInfo.getContainerBaseFolder().getPath()));
    application.stop();
  }

  private void setUpEnvironment() {
    setProperty(MULE_HOME_DIRECTORY_PROPERTY, containerInfo.getContainerBaseFolder().getPath());
    getDomainsFolder().mkdirs();
    getServicesFolder().mkdirs();
    URLClassLoader urlClassLoader = (URLClassLoader) Thread.currentThread().getContextClassLoader();
    URL[] urLs = urlClassLoader.getURLs();
    for (URL urL : urLs) {
      File dependencyFile = new File(urL.getPath());
      if (dependencyFile.getName().endsWith("zip")) {
        try {
          copyFile(dependencyFile, new File(getServicesFolder(), dependencyFile.getName()));
        } catch (IOException e) {
          throw new RuntimeException("Failure copying services", e);
        }
      }
    }
  }

  private File createDefaultDomainDir() {
    File containerFolder = new File(containerInfo.getContainerBaseFolder().getPath());
    File defaultDomainFolder = new File(new File(containerFolder, "domains"), "default");
    if (!defaultDomainFolder.mkdirs()) {
      throw new RuntimeException("Could not create default domain directory in " + defaultDomainFolder.getAbsolutePath());
    }
    return defaultDomainFolder;
  }

  private File createAppDir() {
    File containerFolder = new File(containerInfo.getContainerBaseFolder().getPath());
    File appFolder = new File(new File(containerFolder, "apps"), "default");
    if (!appFolder.mkdirs()) {
      throw new RuntimeException("Could not create default app directory in " + appFolder.getAbsolutePath());
    }
    return appFolder;
  }

}
