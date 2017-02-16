/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.deployment.impl.internal.application;

import static java.lang.String.format;
import static org.mule.runtime.deployment.model.api.plugin.MavenClassLoaderConstants.MAVEN;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.core.config.bootstrap.ArtifactType;
import org.mule.runtime.module.artifact.descriptor.ArtifactDescriptorCreateException;
import org.mule.runtime.module.artifact.descriptor.BundleDependency;
import org.mule.runtime.module.artifact.descriptor.BundleDescriptor;
import org.mule.runtime.module.artifact.descriptor.BundleScope;
import org.mule.runtime.module.artifact.descriptor.ClassLoaderModel;
import org.mule.runtime.module.artifact.descriptor.ClassLoaderModel.ClassLoaderModelBuilder;
import org.mule.runtime.module.deployment.impl.internal.artifact.MavenClassLoaderModelLoader;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is responsible of returning the {@link BundleDescriptor} of a given plugin's location and also creating a
 * {@link ClassLoaderModel} TODO(fernandezlautaro): MULE-11094 this class is the default implementation for discovering
 * dependencies and URLs, which happens to be Maven based. There could be other ways to look for dependencies and URLs (probably
 * for testing purposes where the plugins are done by hand and without maven) which will imply implementing the jira pointed out
 * in this comment.
 *
 * @since 4.0
 */
public class AppMavenClassLoaderModelLoader extends MavenClassLoaderModelLoader {

  protected final Logger logger = LoggerFactory.getLogger(this.getClass());

  @Override
  public String getId() {
    return MAVEN;
  }

  protected void loadDependencies(ClassLoaderModelBuilder classLoaderModelBuilder, PreorderNodeListGenerator nlg) {
    final Set<BundleDependency> plugins = new HashSet<>();
    nlg.getDependencies(true).stream()
        .filter(this::isMulePlugin)
        .map(Dependency::getArtifact)
        .forEach(artifact -> {
          final BundleDescriptor.Builder bundleDescriptorBuilder = new BundleDescriptor.Builder()
              .setArtifactId(artifact.getArtifactId())
              .setGroupId(artifact.getGroupId())
              .setVersion(artifact.getVersion())
              .setType(artifact.getExtension())
              .setClassifier(artifact.getClassifier());

          plugins.add(new BundleDependency.Builder()
              .setDescriptor(bundleDescriptorBuilder.build())
              .setScope(BundleScope.COMPILE)
              .build());
        });
    classLoaderModelBuilder.dependingOn(plugins);
  }

  protected void loadUrls(File pluginFolder, ClassLoaderModelBuilder classLoaderModelBuilder,
                          DependencyResult dependencyResult, PreorderNodeListGenerator nlg) {
    // Adding the exploded JAR root folder
    try {
      classLoaderModelBuilder.containing(new File(pluginFolder, "classes").toURL());
      dependencyResult.getArtifactResults().stream()
          .forEach(artifactResult -> {
            try {
              classLoaderModelBuilder.containing(artifactResult.getArtifact().getFile().toURL());
            } catch (MalformedURLException e) {
              throw new MuleRuntimeException(e);
            }
          });
    } catch (MalformedURLException e) {
      throw new MuleRuntimeException(e);
    }
  }

  private URL getUrl(File pluginFolder, File file) {
    try {
      return file.toURI().toURL();
    } catch (MalformedURLException e) {
      throw new ArtifactDescriptorCreateException(format("There was an issue obtaining the URL for the plugin [%s], file [%s]",
                                                         pluginFolder.getAbsolutePath(), file.getAbsolutePath()),
                                                  e);
    }
  }

  @Override
  public boolean supportsArtifactType(ArtifactType artifactType) {
    return artifactType.equals(ArtifactType.APP);
  }
}
