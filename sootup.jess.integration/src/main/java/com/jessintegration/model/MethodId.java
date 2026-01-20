// src/main/java/com/jessintegration/model/MethodId.java
package com.jessintegration.model;

/** Identifies a method by binary class name + simple name + JVM descriptor. */
public record MethodId(String binaryClassName, String name, String jvmDescriptor) {}
