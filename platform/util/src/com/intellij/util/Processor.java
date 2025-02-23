/*
 * Copyright 2000-2010 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.util;

/**
 * Generic callback with continue/stop semantics.
 *
 * @param <T> Input value type.
 * @see CommonProcessors
 */
public interface Processor<T> {

  /**
   * @see CommonProcessors#alwaysTrue()
   */
  Processor TRUE = o -> true;

  /**
   * @see CommonProcessors#alwaysFalse()
   */
  Processor FALSE = o -> false;

  /**
   * @param t consequently takes value of each element of the set this processor is passed to for processing.
   * @return {@code true} to continue processing or {@code false} to stop.
   */
  boolean process(T t);
}