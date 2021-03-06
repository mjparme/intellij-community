// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.serviceContainer

import com.intellij.util.pico.DefaultPicoContainer
import gnu.trove.THashSet
import org.picocontainer.ComponentAdapter
import org.picocontainer.PicoInitializationException
import org.picocontainer.PicoIntrospectionException
import org.picocontainer.defaults.AmbiguousComponentResolutionException
import org.picocontainer.defaults.TooManySatisfiableConstructorsException
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path

internal fun <T> instantiateUsingPicoContainer(aClass: Class<*>, requestorKey: Any, componentManager: PlatformComponentManagerImpl, parameterResolver: ConstructorParameterResolver): T {
  val result = getGreediestSatisfiableConstructor(aClass, requestorKey, componentManager, parameterResolver)
  try {
    result.first.isAccessible = true
    val parameterTypes = result.second
    @Suppress("UNCHECKED_CAST")
    return result.first.newInstance(*Array(parameterTypes.size) {
      parameterResolver.resolveInstance(componentManager, requestorKey, parameterTypes[it])
    }) as T
  }
  catch (e: InvocationTargetException) {
    throw e.cause ?: e
  }
}

private fun getGreediestSatisfiableConstructor(aClass: Class<*>, requestorKey: Any, componentManager: PlatformComponentManagerImpl, parameterResolver: ConstructorParameterResolver): Pair<Constructor<*>, Array<Class<*>>> {
  var conflicts: MutableSet<Constructor<*>>? = null
  var unsatisfiableDependencyTypes: MutableSet<Array<Class<*>>>? = null
  val sortedMatchingConstructors = getSortedMatchingConstructors(aClass)
  var greediestConstructor: Constructor<*>? = null
  var greediestConstructorParameterTypes: Array<Class<*>>? = null
  var lastSatisfiableConstructorSize = -1
  var unsatisfiedDependencyType: Class<*>? = null

  loop@ for (constructor in sortedMatchingConstructors) {
    if (sortedMatchingConstructors.size > 1 &&
        (constructor.isAnnotationPresent(java.lang.Deprecated::class.java) || constructor.isAnnotationPresent(Deprecated::class.java))) {
      continue
    }

    var failedDependency = false
    val parameterTypes = constructor.parameterTypes

    for (expectedType in parameterTypes) {
      if (expectedType.isPrimitive || expectedType.isEnum || expectedType.isArray ||
          Collection::class.java.isAssignableFrom(expectedType) ||
          expectedType === File::class.java || expectedType === Path::class.java) {
        continue@loop
      }

      // check whether this constructor is satisfiable
      if (parameterResolver.isResolvable(componentManager, requestorKey, expectedType)) {
        continue
      }

      if (unsatisfiableDependencyTypes == null) {
        unsatisfiableDependencyTypes = THashSet()
      }
      unsatisfiableDependencyTypes.add(parameterTypes)
      unsatisfiedDependencyType = expectedType
      failedDependency = true
      break
    }

    if (greediestConstructor != null && parameterTypes.size != lastSatisfiableConstructorSize) {
      if (conflicts.isNullOrEmpty()) {
        // we found our match (greedy and satisfied)
        return Pair(greediestConstructor, greediestConstructorParameterTypes!!)
      }
      else {
        // fits although not greedy
        conflicts.add(constructor)
      }
    }
    else if (!failedDependency && lastSatisfiableConstructorSize == parameterTypes.size) {
      // satisfied and same size as previous one?
      if (conflicts == null) {
        conflicts = THashSet()
      }
      conflicts.add(constructor)
      greediestConstructor?.let {
        conflicts.add(it)
      }
    }
    else if (!failedDependency) {
      greediestConstructor = constructor
      greediestConstructorParameterTypes = parameterTypes
      lastSatisfiableConstructorSize = parameterTypes.size
    }
  }

  when {
    !conflicts.isNullOrEmpty() -> {
      throw TooManySatisfiableConstructorsException(aClass, conflicts)
    }
    greediestConstructor != null -> {
      return Pair(greediestConstructor, greediestConstructorParameterTypes!!)
    }
    !unsatisfiableDependencyTypes.isNullOrEmpty() -> {
      throw PicoIntrospectionException("${aClass.name} has unsatisfied dependency: $unsatisfiedDependencyType among unsatisfiable dependencies: " +
                                       "$unsatisfiableDependencyTypes where $componentManager was the leaf container being asked for dependencies.")
    }
    else -> {
      throw PicoInitializationException("The specified parameters not match any of the following constructors: " +
                                        "${aClass.declaredConstructors.joinToString(separator = "\n") { it.toString() }}\n" +
                                        "for $aClass")
    }
  }
}

private val constructorComparator = Comparator<Constructor<*>> { c0, c1 -> c1.parameterCount - c0.parameterCount }

// filter out all constructors that will definitely not match
// optimize list of constructors moving the longest at the beginning
private fun getSortedMatchingConstructors(componentImplementation: Class<*>): Array<Constructor<*>> {
  val declaredConstructors = componentImplementation.declaredConstructors
  declaredConstructors.sortWith(constructorComparator)
  return declaredConstructors
}

internal abstract class ConstructorParameterResolver {
  open fun isResolvable(componentManager: PlatformComponentManagerImpl, requestorKey: Any, expectedType: Class<*>): Boolean {
    return resolveAdapter(componentManager, requestorKey, expectedType) != null
  }

  open fun resolveInstance(componentManager: PlatformComponentManagerImpl, requestorKey: Any, expectedType: Class<*>): Any? {
    val adapter = resolveAdapter(componentManager, requestorKey, expectedType) ?: return null
    return when (adapter) {
      is BaseComponentAdapter -> {
        // project level service Foo wants application level service Bar - adapter component manager should be used instead of current
        adapter.getInstance(adapter.componentManager)
      }
      else -> {
        if (componentManager.parent == null) {
          adapter.getComponentInstance(componentManager.picoContainer)
        }
        else {
          componentManager.picoContainer.getComponentInstance(adapter.componentKey)
        }
      }
    }
  }

  private fun resolveAdapter(componentManager: PlatformComponentManagerImpl, requestorKey: Any, expectedType: Class<*>): ComponentAdapter? {
    val result = getTargetAdapter(componentManager.picoContainer, expectedType, requestorKey) ?: return null
    if (expectedType.isAssignableFrom(result.componentImplementation)) {
      return result
    }
    return null
  }

  private fun getTargetAdapter(container: DefaultPicoContainer, expectedType: Class<*>, excludeKey: Any): ComponentAdapter? {
    val byKey = container.getComponentAdapter(expectedType)
    if (byKey != null && excludeKey != byKey.componentKey) {
      return byKey
    }

    // see UndoManagerImpl / RunManager / JavaModuleExternalPathsImpl for example
    val expectedClassName = expectedType.name

    if (container.parent == null) {
      if (expectedClassName == "com.intellij.openapi.project.Project") {
        return null
      }
    }
    else {
      if (expectedClassName == "com.intellij.configurationStore.StreamProvider" ||
          expectedClassName == "com.intellij.openapi.roots.LanguageLevelModuleExtensionImpl" ||
          expectedClassName == "com.intellij.openapi.roots.impl.CompilerModuleExtensionImpl" ||
          expectedClassName == "com.intellij.openapi.roots.impl.JavaModuleExternalPathsImpl") {
        return null
      }
    }

    val found = container.getComponentAdaptersOfType(expectedType)
    found.removeIf { it.componentKey == excludeKey }
    return when {
      found.size == 0 -> container.parent?.getComponentAdapterOfType(expectedType)
      found.size == 1 -> found[0]
      else -> throw AmbiguousComponentResolutionException(expectedType, Array(found.size) { found[it].componentImplementation })
    }
  }
}