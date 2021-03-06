/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.rules;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import com.facebook.buck.jvm.java.JavaBinary;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.HumanReadableException;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuildRuleResolverTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void testBuildAndAddToIndexRejectsDuplicateBuildTarget() throws Exception {
    BuildRuleResolver buildRuleResolver =
        new DefaultBuildRuleResolver(
            TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    JavaLibraryBuilder.createBuilder(target).build(buildRuleResolver);

    // A BuildRuleResolver should allow only one entry for a BuildTarget.
    try {
      JavaLibraryBuilder.createBuilder(target).build(buildRuleResolver);
      fail("Should throw IllegalStateException.");
    } catch (IllegalStateException e) {
      assertEquals(
          "A build rule for this target has already been created: " + target, e.getMessage());
    }
  }

  @Test
  public void testRequireNonExistingBuildRule() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    TargetNode<?, ?> library = JavaLibraryBuilder.createBuilder(target).build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(library);
    BuildRuleResolver resolver =
        new DefaultBuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());

    BuildRule rule = resolver.requireRule(target);
    assertThat(rule, is(notNullValue()));
    assertThat(rule.getBuildTarget(), is(equalTo(target)));
  }

  @Test
  public void testRequireExistingBuildRule() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    JavaLibraryBuilder builder = JavaLibraryBuilder.createBuilder(target);
    TargetNode<?, ?> library = builder.build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(library);
    BuildRuleResolver resolver =
        new DefaultBuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    BuildRule existing = builder.build(resolver);

    assertThat(resolver.getRuleOptional(target).isPresent(), is(true));

    BuildRule rule = resolver.requireRule(target);
    assertThat(rule, is(notNullValue()));
    assertThat(rule.getBuildTarget(), is(equalTo(target)));
    assertThat(rule, is(equalTo(existing)));
  }

  @Test
  public void getRuleWithTypeMissingRule() throws Exception {
    BuildRuleResolver resolver =
        new DefaultBuildRuleResolver(
            TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(Matchers.containsString("could not be resolved"));
    resolver.getRuleWithType(BuildTargetFactory.newInstance("//:non-existent"), BuildRule.class);
  }

  @Test
  public void getRuleWithTypeWrongType() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    JavaLibraryBuilder builder = JavaLibraryBuilder.createBuilder(target);
    TargetNode<?, ?> library = builder.build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(library);
    BuildRuleResolver resolver =
        new DefaultBuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    builder.build(resolver);
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(Matchers.containsString("not of expected type"));
    resolver.getRuleWithType(BuildTargetFactory.newInstance("//foo:bar"), JavaBinary.class);
  }

  @Test
  public void computeIfAbsentComputesOnlyIfAbsent() {
    BuildRuleResolver resolver =
        new DefaultBuildRuleResolver(
            TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    AtomicInteger supplierInvoked = new AtomicInteger(0);
    BuildRule buildRule =
        new NoopBuildRuleWithDeclaredAndExtraDeps(
            target, new FakeProjectFilesystem(), TestBuildRuleParams.create());
    BuildRule returnedBuildRule =
        resolver.computeIfAbsent(
            target,
            passedTarget -> {
              assertEquals(passedTarget, target);
              supplierInvoked.incrementAndGet();
              return buildRule;
            });
    assertEquals("supplier was called once", supplierInvoked.get(), 1);
    assertSame("returned the same build rule that was generated", returnedBuildRule, buildRule);
    assertSame("the rule can be retrieved again", resolver.getRule(target), buildRule);
    returnedBuildRule =
        resolver.computeIfAbsent(
            target,
            passedTarget -> {
              assertEquals(passedTarget, target);
              supplierInvoked.incrementAndGet();
              return buildRule;
            });
    assertEquals("supplier is not called again", supplierInvoked.get(), 1);
    assertSame("recorded rule is still returned", returnedBuildRule, buildRule);
  }
}
