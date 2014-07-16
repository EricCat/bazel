// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.skyframe;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.Map;

/**
 * Immutable implementation of {@link Differencer.Diff}.
 */
public class ImmutableDiff implements Differencer.Diff {

  private final ImmutableList<NodeKey> nodesToInvalidate;
  private final ImmutableMap<NodeKey, Node> nodesToInject;

  public ImmutableDiff(Iterable<NodeKey> nodesToInvalidate, Map<NodeKey, Node> nodesToInject) {
    this.nodesToInvalidate = ImmutableList.copyOf(nodesToInvalidate);
    this.nodesToInject = ImmutableMap.copyOf(nodesToInject);
  }

  @Override
  public Iterable<NodeKey> changedKeysWithoutNewValues() {
    return nodesToInvalidate;
  }

  @Override
  public Map<NodeKey, Node> changedKeysWithNewValues() {
    return nodesToInject;
  }
}