package com.teamscale.profiler.installer;

import org.conqat.lib.commons.filesystem.FileSystemUtils;
import org.conqat.lib.commons.system.SystemUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

import static java.nio.file.attribute.PosixFilePermission.OTHERS_READ;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstallerTest {

	private static final int TEAMSCALE_PORT = 8054;
	private static final String FILE_TO_INSTALL_CONTENT = "install-me";
	private static final String NESTED_FILE_CONTENT = "nested-file";
	private static final String TEAMSCALE_URL = "http://localhost:" + TEAMSCALE_PORT + "/";


	private Path sourceDirectory;
	private Path targetDirectory;

	private Path fileToInstall;
	private Path nestedFileToInstall;

	private Path installedFile;
	private Path installedNestedFile;
	private Path installedTeamscaleProperties;
	private Path installedCoverageDirectory;
	private Path installedLogsDirectory;

	private static MockTeamscale TEAMSCALE;

	@BeforeEach
	void setUpSourceDirectory() throws IOException {
		sourceDirectory = Files.createTempDirectory("InstallerTest-source");
		targetDirectory = Files.createTempDirectory("InstallerTest-target").resolve("profiler");

		fileToInstall = sourceDirectory.resolve("install-me.txt");
		Files.write(fileToInstall, FILE_TO_INSTALL_CONTENT.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);

		nestedFileToInstall = sourceDirectory.resolve("subfolder/nested-file.txt");
		Files.createDirectories(nestedFileToInstall.getParent());
		Files.write(nestedFileToInstall, NESTED_FILE_CONTENT.getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.CREATE);

		installedFile = targetDirectory.resolve(sourceDirectory.relativize(fileToInstall));
		installedNestedFile = targetDirectory.resolve(sourceDirectory.relativize(nestedFileToInstall));
		installedTeamscaleProperties = targetDirectory.resolve("teamscale.properties");
		installedCoverageDirectory = targetDirectory.resolve("coverage");
		installedLogsDirectory = targetDirectory.resolve("logs");
	}

	@BeforeAll
	static void startFakeTeamscale() {
		TEAMSCALE = new MockTeamscale(TEAMSCALE_PORT);
	}

	@AfterAll
	static void stopFakeTeamscale() {
		TEAMSCALE.shutdown();
	}

	@Test
	void successfulInstallation() throws FatalInstallerError, IOException {
		install();

		assertThat(installedFile).exists().content().isEqualTo(FILE_TO_INSTALL_CONTENT);
		assertThat(installedNestedFile).exists().content().isEqualTo(NESTED_FILE_CONTENT);

		assertThat(installedTeamscaleProperties).exists();
		Properties properties = FileSystemUtils.readProperties(installedTeamscaleProperties.toFile());
		assertThat(properties.keySet()).containsExactlyInAnyOrder("url", "username", "accesskey");
		assertThat(properties.getProperty("url")).isEqualTo(TEAMSCALE_URL);
		assertThat(properties.getProperty("username")).isEqualTo("user");
		assertThat(properties.getProperty("accesskey")).isEqualTo("accesskey");

		assertThat(installedCoverageDirectory).exists().isReadable().isWritable();
		assertThat(installedLogsDirectory).exists().isReadable().isWritable();

		if (SystemUtils.isLinux()) {
			assertThat(Files.getPosixFilePermissions(installedFile)).contains(OTHERS_READ);
			assertThat(Files.getPosixFilePermissions(installedLogsDirectory)).contains(OTHERS_READ, OTHERS_WRITE);
			assertThat(Files.getPosixFilePermissions(installedCoverageDirectory)).contains(OTHERS_READ, OTHERS_WRITE);
		}
		// TODO (FS) windows
	}

	@Test
	void nonexistantTeamscaleUrl() {
		assertThatThrownBy(() -> install("http://does-not-exist:8080"))
				.hasMessageContaining("could not be resolved");
		assertThat(targetDirectory).doesNotExist();
	}

	@Test
	void connectionRefused() {
		assertThatThrownBy(() -> install("http://localhost:" + (TEAMSCALE_PORT + 1)))
				.hasMessageContaining("refused a connection");
		assertThat(targetDirectory).doesNotExist();
	}

	@Test
	void httpsInsteadOfHttp() {
		assertThatThrownBy(() -> install("https://localhost:" + TEAMSCALE_PORT))
				.hasMessageContaining("configured for HTTPS, not HTTP");
		assertThat(targetDirectory).doesNotExist();
	}

	@Test
	void profilerAlreadyInstalled() throws IOException {
		Files.createDirectories(targetDirectory);
		assertThatThrownBy(this::install).hasMessageContaining("Path already exists");
	}

	@Test
	void installDirectoryNotWritable() {
		assertThat(targetDirectory.getParent().toFile().setReadOnly()).isTrue();
		assertThatThrownBy(() -> install(TEAMSCALE_URL)).hasMessageContaining("Cannot create installation directory");
	}

	private void install() throws FatalInstallerError {
		install(TEAMSCALE_URL);
	}

	private void install(String teamscaleUrl) throws FatalInstallerError {
		new Installer(sourceDirectory, targetDirectory).run(new String[]{teamscaleUrl, "user", "accesskey"});
	}

}
