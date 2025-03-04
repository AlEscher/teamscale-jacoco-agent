package com.teamscale.tia;

import org.conqat.lib.commons.filesystem.FileSystemUtils;
import org.conqat.lib.commons.io.ProcessUtils;
import org.conqat.lib.commons.system.SystemUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class to check if multiple maven plugins can be started with dynamic port allocation.
 */
public class TiaMavenMultipleJobsTest {

	/**
	 * Starts multiple maven processes and checks that the ports are dynamically set and the servers are correctly
	 * started.
	 */
	@Test
	public void testMavenTia() throws Exception {
		File workingDirectory = new File("maven-dump-local-project");
		String executable = "./mvnw";
		if (SystemUtils.isWindows()) {
			executable = Paths.get("maven-dump-local-project", "mvnw.cmd").toUri().getPath();
		}
		// Clean once before testing parallel execution and make sure that the cleaning
		// process is finished before testing.
		ProcessUtils.ExecutionResult result = ProcessUtils
				.execute(new ProcessBuilder(executable, "clean").directory(workingDirectory));
		assertThat(result.terminatedByTimeoutOrInterruption()).isFalse();
		for (int i = 0; i < 3; i++) {
			new ProcessBuilder(executable, "verify").directory(workingDirectory).start();
		}
		result = ProcessUtils
				.execute(new ProcessBuilder(executable, "verify").directory(workingDirectory));
		// Get additional output for error debugging.
		System.out.println(result.getStdout());
		File configFile = new File(Paths.get(workingDirectory.getAbsolutePath(), "target", "tia", "agent.log").toUri());
		String configContent = FileSystemUtils.readFile(configFile);
		assertThat(configContent).isNotEmpty();
		assertThat(result.terminatedByTimeoutOrInterruption()).isFalse();
		assertThat(configContent).doesNotContain("Could not start http server on port");
	}
}
