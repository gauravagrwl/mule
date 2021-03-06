/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.tck.testmodels.mule;

import org.mule.runtime.core.routing.SimpleCollectionAggregator;


/**
 * <code>TestResponseAggregator</code> is a mock response Agrregator object used for testing configuration
 */
public class TestResponseAggregator extends SimpleCollectionAggregator {

  private String testProperty;

  public String getTestProperty() {
    return testProperty;
  }

  public void setTestProperty(String testProperty) {
    this.testProperty = testProperty;
  }
}
