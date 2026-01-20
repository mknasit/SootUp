package com.jessintegration.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ProjectListConfig {
  private static final ObjectMapper OM = new ObjectMapper();

  public static List<ProjectEntry> load(Path jsonArrayFile) throws Exception {
    String s = Files.readString(jsonArrayFile);
    return OM.readValue(s, new TypeReference<List<ProjectEntry>>() {});
  }
}
