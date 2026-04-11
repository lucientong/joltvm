/*
 * Copyright 2026 lucientong.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Spring Boot awareness support for JoltVM.
 *
 * <p>This package provides reflection-based Spring context inspection
 * with <b>zero compile-time Spring dependencies</b>. All Spring classes
 * and annotations are discovered and accessed purely via reflection.
 *
 * <p>Key class:
 * <ul>
 *   <li>{@link com.joltvm.server.spring.SpringContextService} — discovers
 *       ApplicationContext, lists beans, parses {@code @RequestMapping},
 *       and analyzes {@code @Controller → @Service → @Repository} dependency chains</li>
 * </ul>
 *
 * @see com.joltvm.server.handler.BeanListHandler
 * @see com.joltvm.server.handler.BeanDetailHandler
 * @see com.joltvm.server.handler.RequestMappingHandler
 * @see com.joltvm.server.handler.DependencyChainHandler
 * @see com.joltvm.server.handler.DependencyGraphHandler
 */
package com.joltvm.server.spring;
