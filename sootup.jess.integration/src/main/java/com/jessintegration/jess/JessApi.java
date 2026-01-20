package com.jessintegration.jess;

import com.jessintegration.model.JessOptions;
import com.jessintegration.model.JessResult;
import com.jessintegration.model.MethodId;
import java.nio.file.Path;

public interface JessApi {
  JessResult compileMethod(Path repoRoot, MethodId method, JessOptions options);

  default JessResult compileMethod(
      Path repoRoot, String sourceRoot, MethodId method, JessOptions options) {
    return compileMethod(repoRoot, method, options);
  }
}
