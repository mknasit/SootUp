package com.jessintegration.model;

import java.nio.file.Path;
import java.util.List;

/** Options for a JESS invocation. */
public record JessOptions(
    String depMode, // e.g., "maven" | "none"
    String sliceMode, // "method" | "class"
    int timeoutSec,
    List<String> extraClasspath,
    Path workDir) {}
