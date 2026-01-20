package com.jessintegration.model;

import java.nio.file.Path;
import java.util.List;

public record JessResult(
    Status status,
    Path classesOutDir, // where .class files are written
    String targetClass, // owner class (binary name)
    List<String> emittedClasses,
    boolean usedStubs,
    boolean depsResolved,
    long elapsedMs,
    String notes) {
  public enum Status {
    OK,
    FAILED_PARSE,
    FAILED_RESOLVE,
    FAILED_COMPILE,
    INTERNAL_ERROR
  }
}
