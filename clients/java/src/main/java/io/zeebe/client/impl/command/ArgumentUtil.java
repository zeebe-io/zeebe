/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
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
package io.zeebe.client.impl.command;

public final class ArgumentUtil {

  public static void ensureNotNull(final String property, final Object value) {
    if (value == null) {
      throw new IllegalArgumentException(property + " must not be null");
    }
  }

  public static void ensureNotEmpty(final String property, final String value) {
    if (value.isEmpty()) {
      throw new IllegalArgumentException(property + " must not be empty");
    }
  }

  public static void ensureNotNullNorEmpty(final String property, final String value) {
    ensureNotNull(property, value);
    ensureNotEmpty(property, value);
  }

  public static void ensureGreaterThan(
      final String property, final long testValue, final long comparisonValue) {
    if (testValue <= comparisonValue) {
      throw new IllegalArgumentException(property + " must be greater than " + comparisonValue);
    }
  }

  public static void ensureGreaterThanOrEqualTo(
      final String property, final float testValue, final float comparisonValue) {
    if (testValue < comparisonValue) {
      throw new IllegalArgumentException(property + " must be greater than " + comparisonValue);
    }
  }
}
