/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.runtime.module.embedded.api;

import static java.lang.String.format;
import static java.lang.System.getProperty;
import static java.util.stream.Collectors.toList;
import static org.eclipse.aether.repository.RepositoryPolicy.CHECKSUM_POLICY_IGNORE;
import static org.eclipse.aether.repository.RepositoryPolicy.UPDATE_POLICY_NEVER;
import static org.eclipse.aether.util.artifact.ArtifactIdUtils.toId;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.PatternInclusionsDependencyFilter;
import org.eclipse.aether.util.filter.ScopeDependencyFilter;
import org.eclipse.aether.util.graph.visitor.PathRecordingDependencyVisitor;
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates a {@link List} of {@link URL}'s for the Container.
 *
 * @since 4.0
 */
public class MavenContainerClassPathFactory {

  private static final String USER_HOME = "user.home";
  private static final String M2_REPO = "/.m2/repository";
  private static final String CONTAINER_BOM_GROUP_ID = "org.mule";
  private static final String CONTAINER_BOM_ARTIFACT_ID = "mule-bom";
  public static final String MULE_SERVICE = "mule-service";

  private static String userHome = getProperty(USER_HOME);

  private static final String MAVEN_REPOSITORY_FOLDER = userHome + M2_REPO;
  protected final Logger logger = LoggerFactory.getLogger(this.getClass());

  private DefaultRepositorySystemSession session;
  private RepositorySystem system;

  /**
   * Creates the {@link List} of {@link URL}s for the Container dependencies for a given version.
   *
   * @param version Maven version. Not null.
   * @return a {@link List} of {@link URL}'s loaded with all its dependencies.
   */
  public List<URL> createClassPath(String version) {
    Artifact defaultArtifact = new DefaultArtifact(CONTAINER_BOM_GROUP_ID, CONTAINER_BOM_ARTIFACT_ID,
                                                   null,
                                                   "pom",
                                                   version);

    final PreorderNodeListGenerator nlg = assemblyDependenciesForArtifact(defaultArtifact);
    return loadUrls(nlg);
  }

  private List<URL> loadUrls(PreorderNodeListGenerator nlg) {
    return nlg.getArtifacts(false)
        .stream()
        .filter(artifact -> !isMuleService(artifact))
        .map(artifact -> getUrl(artifact.getFile())).collect(Collectors.toList());
  }

  private boolean isMuleService(Artifact artifact) {
    return artifact.getClassifier().equals(MULE_SERVICE);
  }

  private PreorderNodeListGenerator assemblyDependenciesForArtifact(Artifact artifact) {
    createRepositorySystem();
    final CollectRequest collectRequest = new CollectRequest();
    try {
      final ArtifactDescriptorResult artifactDescriptorResult =
          system.readArtifactDescriptor(session, new ArtifactDescriptorRequest(artifact, null, null));
      collectRequest.setDependencies(artifactDescriptorResult.getDependencies());
      collectRequest.setManagedDependencies(artifactDescriptorResult.getManagedDependencies());

      final CollectResult collectResult = system.collectDependencies(session, collectRequest);

      final DependencyRequest dependencyRequest = new DependencyRequest();
      dependencyRequest.setFilter(new ScopeDependencyFilter(JavaScopes.TEST, JavaScopes.PROVIDED));
      dependencyRequest.setRoot(collectResult.getRoot());
      dependencyRequest.setCollectRequest(collectRequest);
      final DependencyResult dependencyResult = system.resolveDependencies(session, dependencyRequest);
      final PreorderNodeListGenerator nlg = new PreorderNodeListGenerator();
      dependencyResult.getRoot().accept(nlg);
      return nlg;
    } catch (DependencyResolutionException e) {
      DependencyNode node = e.getResult().getRoot();
      logUnresolvedArtifacts(node, e);
      throw new RuntimeException(
          String.format("There was an issue solving the dependencies for the container [%s]",
                        artifact),
          e);
    } catch (DependencyCollectionException e) {
      throw new RuntimeException(
          String.format("There was an issue resolving the dependency tree for the container [%s]",
                        artifact),
          e);
    } catch (ArtifactDescriptorException e) {
      throw new RuntimeException(
          String.format("There was an issue resolving the artifact descriptor for the container [%s]",
                        artifact),
          e);
    }
  }

  private URL getUrl(File file) {
    try {
      return file.toURI().toURL();
    } catch (MalformedURLException e) {
      throw new RuntimeException(format("There was an issue obtaining the URL for the dependency file [%s]",
                                                         file.getAbsolutePath()),
                                                  e);
    }
  }

  private void createRepositorySystem() {

    session = newDefaultRepositorySystemSession();
    session.setOffline(true);
    session.setIgnoreArtifactDescriptorRepositories(true);

    File mavenLocalRepositoryLocation = new File(MAVEN_REPOSITORY_FOLDER);
    system = newRepositorySystem(mavenLocalRepositoryLocation, session);
  }

  private static DefaultRepositorySystemSession newDefaultRepositorySystemSession() {
    final DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
    session.setUpdatePolicy(UPDATE_POLICY_NEVER);
    session.setChecksumPolicy(CHECKSUM_POLICY_IGNORE);
    return session;
  }

  private static RepositorySystem newRepositorySystem(File mavenLocalRepositoryLocation, DefaultRepositorySystemSession session) {
    final RepositorySystem system = newRepositorySystem();
    // We have to set to use a "simple" aether local repository so it will not cache artifacts (enhanced is supported for doing
    // operations such install).
    final LocalRepository localRepo = new LocalRepository(mavenLocalRepositoryLocation, "simple");
    session
        .setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
    return system;
  }

  /**
   * Creates and configures the {@link RepositorySystem} to use for resolving transitive dependencies.
   *
   * @return {@link RepositorySystem}
   */
  private static RepositorySystem newRepositorySystem() {
    /*
     * Aether's components implement org.eclipse.aether.spi.locator.Service to ease manual wiring and using the pre populated
     * DefaultServiceLocator, we only MavenXpp3Reader need to register the repository connector and transporter factories.
     */
    final DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
    locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
    locator.addService(TransporterFactory.class, FileTransporterFactory.class);
    return locator.getService(RepositorySystem.class);
  }

  private void logUnresolvedArtifacts(DependencyNode node, DependencyResolutionException e) {
    List<ArtifactResult> artifactResults = e.getResult().getArtifactResults().stream()
        .filter(artifactResult -> !artifactResult.getExceptions().isEmpty()).collect(toList());

    final List<String> patternInclusion =
        artifactResults.stream().map(artifactResult ->
                                         toId(artifactResult.getRequest().getArtifact())).collect(toList());

    PathRecordingDependencyVisitor visitor =
        new PathRecordingDependencyVisitor(new PatternInclusionsDependencyFilter(patternInclusion), node.getArtifact() != null);
    node.accept(visitor);

    visitor.getPaths().stream().forEach(path -> {
      List<DependencyNode> unresolvedArtifactPath =
          path.stream().filter(dependencyNode -> dependencyNode.getArtifact() != null).collect(toList());
      if (!unresolvedArtifactPath.isEmpty()) {
        logger.warn("Dependency path to not resolved artifacts -> " + unresolvedArtifactPath.toString());
      }
    });
  }

}
