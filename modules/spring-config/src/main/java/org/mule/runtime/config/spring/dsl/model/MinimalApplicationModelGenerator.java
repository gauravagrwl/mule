/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.config.spring.dsl.model;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import org.mule.runtime.api.component.location.Location;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.util.Reference;
import org.mule.runtime.config.spring.dsl.processor.AbstractAttributeDefinitionVisitor;
import org.mule.runtime.dsl.api.component.ComponentBuildingDefinition;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;

/**
 * Generates the minimal required component set to create a configuration component (i.e.: file:config, ftp:connection, a flow
 * MP). This set is defined by the component dependencies.
 * <p/>
 * Based on the requested component, the {@link ComponentModel} configuration associated is introspected to find it dependencies
 * based on it's {@link org.mule.runtime.dsl.api.component.ComponentBuildingDefinition}. This process is recursively done for each
 * of the dependencies in order to find all the required {@link ComponentModel}s that must be created for the requested
 * {@link ComponentModel} to work properly.
 *
 * @since 4.0
 */
// TODO MULE-9688 - refactor this class when the ComponentModel becomes immutable
public class MinimalApplicationModelGenerator {

  private final ComponentBuildingDefinitionRegistry componentBuildingDefinitionRegistry;
  private final ApplicationModel applicationModel;

  /**
   * Creates a new instance associated to a complete {@link ApplicationModel}.
   *
   * @param applicationModel the artifact {@link ApplicationModel}.
   * @param componentBuildingDefinitionRegistry the registry to find the
   *        {@link org.mule.runtime.dsl.api.component.ComponentBuildingDefinition}s associated to each {@link ComponentModel} that
   *        must be resolved.
   */
  public MinimalApplicationModelGenerator(ApplicationModel applicationModel,
                                          ComponentBuildingDefinitionRegistry componentBuildingDefinitionRegistry) {
    this.applicationModel = applicationModel;
    this.componentBuildingDefinitionRegistry = componentBuildingDefinitionRegistry;
  }

  /**
   * Resolves the minimal set of {@link ComponentModel}s for a component within a flow.
   *
   * @param location the component path in which the component is located.
   * @return the generated {@link ApplicationModel} with the minimal set of {@link ComponentModel}s required.
   * @throws NoSuchComponentModelException if the requested component does not exists.
   */
  public ApplicationModel getMinimalModel(Location location) {
    ComponentModel requestedComponentModel = findRequiredComponentModel(location);
    final Set<String> otherRequiredGlobalComponents = resolveComponentDependencies(requestedComponentModel);
    otherRequiredGlobalComponents.add(location.getGlobalComponentName());
    Set<String> allRequiredComponentModels = findComponentModelsDependencies(otherRequiredGlobalComponents);
    Iterator<ComponentModel> iterator = applicationModel.getRootComponentModel().getInnerComponents().iterator();
    while (iterator.hasNext()) {
      ComponentModel componentModel = iterator.next();
      if (componentModel.getNameAttribute() == null || !allRequiredComponentModels.contains(componentModel.getNameAttribute())) {
        iterator.remove();
      }
    }
    return applicationModel;
  }

  private ComponentModel findRequiredComponentModel(String name) {
    return applicationModel.findNamedComponent(name)
        .orElseThrow(() -> new NoSuchComponentModelException(createStaticMessage("No named component with name " + name)));
  }

  private ComponentModel findRequiredComponentModel(Location location) {
    final Reference<ComponentModel> foundComponentModelReference = new Reference<>();
    Optional<ComponentModel> globalComponent = applicationModel.findNamedComponent(location.getGlobalComponentName());
    globalComponent.ifPresent(componentModel -> {
      findComponentWithLocation(componentModel, location).ifPresent(foundComponentModel -> {
        foundComponentModelReference.set(foundComponentModel);
      });
    });
    return foundComponentModelReference.get();
  }

  private Optional<ComponentModel> findComponentWithLocation(ComponentModel componentModel, Location location) {
    if (componentModel.getComponentLocation().getLocation().equals(location.toString())) {
      return of(componentModel);
    }
    for (ComponentModel childComponent : componentModel.getInnerComponents()) {
      Optional<ComponentModel> foundComponent = findComponentWithLocation(childComponent, location);
      if (foundComponent.isPresent()) {
        return foundComponent;
      }
    }
    return empty();
  }

  private Set<String> findComponentModelsDependencies(Set<String> componentModelNames) {
    Set<String> componentsToSearchDependencies = componentModelNames;
    Set<String> foundDependencies = new HashSet<>();
    Set<String> alreadySearchedDependencies = new HashSet<>();
    do {
      componentsToSearchDependencies.addAll(foundDependencies);
      for (String componentModelName : componentsToSearchDependencies) {
        if (!alreadySearchedDependencies.contains(componentModelName)) {
          alreadySearchedDependencies.add(componentModelName);
          foundDependencies.addAll(resolveComponentDependencies(findRequiredComponentModel(componentModelName)));
        }
      }
      foundDependencies.addAll(componentModelNames);

    } while (!foundDependencies.containsAll(componentsToSearchDependencies));
    return foundDependencies;
  }

  private Set<String> resolveComponentDependencies(ComponentModel requestedComponentModel) {
    Set<String> otherDependencies = new HashSet<>();
    requestedComponentModel.getInnerComponents()
        .stream().forEach(childComponent -> {
          otherDependencies.addAll(resolveComponentDependencies(childComponent));
        });
    final Set<String> parametersReferencingDependencies = new HashSet<>();
    // TODO MULE-10516 - Remove one the config-ref attribute is defined as a reference
    parametersReferencingDependencies.add("config-ref");
    ComponentBuildingDefinition buildingDefinition =
        componentBuildingDefinitionRegistry.getBuildingDefinition(requestedComponentModel.getIdentifier())
            .orElseThrow(() -> new MuleRuntimeException(createStaticMessage("No component building definition for component "
                + requestedComponentModel.getIdentifier())));

    buildingDefinition.getAttributesDefinitions()
        .stream().forEach(attributeDefinition -> {
          attributeDefinition.accept(new AbstractAttributeDefinitionVisitor() {

            @Override
            public void onReferenceSimpleParameter(String reference) {
              parametersReferencingDependencies.add(reference);
            }
          });
        });

    for (String parametersReferencingDependency : parametersReferencingDependencies) {
      if (requestedComponentModel.getParameters().containsKey(parametersReferencingDependency)) {
        otherDependencies.add(requestedComponentModel.getParameters().get(parametersReferencingDependency));
      }
    }
    return otherDependencies;
  }


}
