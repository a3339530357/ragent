/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.framework.runtime;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(-100)
public class RuntimeConfigBoundaryAspect {
    @Around(
            "@annotation(com.nageoffer.ai.ragent.framework.runtime.RuntimeConfigBoundary) ||"
                    + " @within(com.nageoffer.ai.ragent.framework.runtime.RuntimeConfigBoundary)")
    public Object pin(ProceedingJoinPoint point) throws Throwable {
        try (RuntimeConfigContext.Scope ignored =
                RuntimeConfigContext.use(RuntimeConfigContext.current())) {
            return point.proceed();
        }
    }
}
