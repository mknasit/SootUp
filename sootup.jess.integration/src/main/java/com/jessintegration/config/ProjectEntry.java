package com.jessintegration.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.file.Path;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class ProjectEntry {
  public String name;
  public Path projectDir; // required
  public List<String> sourceRoots; // e.g. ["src/main/java", "src/test/java"]
  public List<String> classpathJars; // extra CP jars (absolute or relative to projectDir)
  public String pkg; // optional: filter e.g. "org.apache.commons.io"
  public Integer limit; // optional: default 100
  public Path workDir; // optional: default projectDir/pc-out
}
