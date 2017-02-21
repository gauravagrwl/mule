/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.runtime.module.embedded.api;

import java.io.Serializable;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class ArtifactInfo implements Serializable {

  private final List<URI> configs;
  private final URL classesFolder;
  private final URL pomFile;
  private final URL descriptorFile;
  private final boolean enableAppTestDependencies;
  private final Map<String, String> applicationProperties;

  public ArtifactInfo(List<URI> configs, URL classesFolder, URL pomFile, URL descriptorFile,
                      Map<String, String> applicationProperties, boolean enableAppTestDependencies) {
    this.configs = configs;
    this.classesFolder = classesFolder;
    this.pomFile = pomFile;
    this.descriptorFile = descriptorFile;
    this.applicationProperties = applicationProperties;
    this.enableAppTestDependencies = enableAppTestDependencies;
  }

  public boolean isEnableAppTestDependencies()
  {
    return enableAppTestDependencies;
  }

  public Map<String, String> getApplicationProperties() {
    return applicationProperties;
  }

  public List<URI> getConfigs() {
    return configs;
  }

  public URL getClassesFolder() {
    return classesFolder;
  }

  public URL getPomFile() {
    return pomFile;
  }

  public URL getDescriptorFile() {
    return descriptorFile;
  }
}
