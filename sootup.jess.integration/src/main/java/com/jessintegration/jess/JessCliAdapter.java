package com.jessintegration.jess;

import com.jessintegration.model.JessOptions;
import com.jessintegration.model.JessResult;
import com.jessintegration.model.MethodId;
import java.nio.file.Path;
import java.util.List;

/**
 * Placeholder CLI adapter. If you don't have an external 'jess' CLI yet, either keep this class
 * unused, or adapt it later. It returns INTERNAL_ERROR for now.
 */
public final class JessCliAdapter implements JessApi {

  private final Path jessExecutable;

  public JessCliAdapter(Path jessExecutable) {
    this.jessExecutable = jessExecutable;
  }

  @Override
  public JessResult compileMethod(Path repoRoot, MethodId method, JessOptions options) {
    // TODO: implement actual CLI call; for now, a safe stub so compilation succeeds
    return new JessResult(
        JessResult.Status.INTERNAL_ERROR,
        null,
        null,
        List.of(),
        false,
        false,
        0L,
        "JessCliAdapter not implemented");
  }

  // If you implement it later, you'd need to:
  // 1. Add imports: org.zeroturnaround.exec.ProcessExecutor and Slf4jStream
  // 2. Implement runCli method to execute jess CLI and parse JSON output
  // 3. Map CLI results to JessResult
}
