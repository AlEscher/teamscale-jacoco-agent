package com.teamscale.maven.upload;

import com.teamscale.maven.TeamscaleMojoBase;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import shadow.com.teamscale.client.CommitDescriptor;
import shadow.com.teamscale.client.EReportFormat;
import shadow.com.teamscale.client.TeamscaleClient;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Run this goal after the Jacoco report generation to upload them to a configured Teamscale instance.
 * The configuration can be specified in the root Maven project.
 * Offers the following functionality:
 * <ol>
 *     <li>Validate Jacoco Maven plugin configuration</li>
 *     <li>Locate and upload all reports in one session</li>
 * </ol>
 * @see <a href="https://www.jacoco.org/jacoco/trunk/doc/maven.html">Jacoco Plugin</a>
 */
@Mojo(name = "upload-coverage", defaultPhase = LifecyclePhase.VERIFY, requiresDependencyResolution = ResolutionScope.RUNTIME,
		threadSafe = true)
public class CoverageUploadMojo extends TeamscaleMojoBase {

	private static final String JACOCO_PLUGIN_NAME = "org.jacoco:jacoco-maven-plugin";

	private static final String COVERAGE_UPLOAD_MESSAGE = "Coverage upload via Teamscale Maven plugin";

	/**
	 * The Teamscale partition name to which unit test reports will be uploaded
	 */
	@Parameter(property = "teamscale.unitTestPartition", defaultValue = "Unit Tests")
	public String unitTestPartition;

	/**
	 * The Teamscale partition name to which integration test reports will be uploaded
	 */
	@Parameter(property = "teamscale.integrationTestPartition", defaultValue = "Integration Tests")
	public String integrationTestPartition;

	/**
	 * The Teamscale partition name to which aggregated test reports will be uploaded
	 */
	@Parameter(property = "teamscale.aggregatedTestPartition", defaultValue = "Aggregated Tests")
	public String aggregatedTestPartition;

	/**
	 * Paths to all reports generated by subprojects
	 * @see <a href="https://www.jacoco.org/jacoco/trunk/doc/report-mojo.html">report</a>
	 */
	private final List<Path> reportGoalOutputFiles = new ArrayList<>();

	/**
	 * Paths to all integration reports generated by subprojects
	 * @see <a href="https://www.jacoco.org/jacoco/trunk/doc/report-integration-mojo.html">report-integration</a>
	 */
	private final List<Path> reportIntegrationGoalOutputFiles = new ArrayList<>();

	/**
	 * Paths to all aggregated reports generated by subprojects
	 * @see <a href="https://www.jacoco.org/jacoco/trunk/doc/report-aggregate-mojo.html">report-aggregate</a>
	 */
	private final List<Path> reportAggregateGoalOutputFiles = new ArrayList<>();

	/**
	 * The Teamscale client that is used to upload reports to a Teamscale instance.
	 */
	private TeamscaleClient teamscaleClient;

	@Override
	public void execute() throws MojoFailureException {
		if (skip) {
			getLog().debug("Skipping since skip is set to true");
			return;
		}
		if (!session.getCurrentProject().equals(session.getTopLevelProject())) {
			getLog().debug("Skipping since upload should only happen in top project");
			return;
		}
		teamscaleClient = new TeamscaleClient(teamscaleUrl, username, accessToken, projectId);
		getLog().debug("Resolving end commit");
		resolveEndCommit();
		getLog().debug("Parsing Jacoco plugin configurations");
		parseJacocoConfiguration();
		try {
			getLog().debug("Uploading coverage reports");
			uploadCoverageReports();
		} catch (IOException e) {
			throw new MojoFailureException("Uploading coverage reports failed. No upload to Teamscale was performed. You can try again or upload the XML coverage reports manually, see https://docs.teamscale.com/reference/ui/project/project/#manual-report-upload", e);
		}
	}

	/**
	 * Check that Jacoco is set up correctly and read any custom settings that may have been set
	 * @throws MojoFailureException If Jacoco is not set up correctly
	 */
	private void parseJacocoConfiguration() throws MojoFailureException {
		final String jacocoXML = "jacoco.xml";
		for (MavenProject subProject : session.getTopLevelProject().getCollectedProjects()) {
			Path defaultOutputDirectory = Paths.get(subProject.getReporting().getOutputDirectory());
			// If a Dom is null it means the execution goal uses default parameters which work correctly
			Xpp3Dom reportConfigurationDom = getJacocoGoalExecutionConfiguration(subProject,"report");
			String errorMessage = "Skipping upload for %s as %s is not configured to produce XML reports for goal %s. See https://www.jacoco.org/jacoco/trunk/doc/report-mojo.html#formats";
			if (!validateReportFormat(reportConfigurationDom)) {
				throw new MojoFailureException(String.format(errorMessage, subProject.getName(), JACOCO_PLUGIN_NAME, "report"));
			}
			reportGoalOutputFiles.add(getCustomOutputDirectory(reportConfigurationDom).orElse(defaultOutputDirectory.resolve("jacoco").resolve(jacocoXML)));

			Xpp3Dom reportIntegrationConfigurationDom = getJacocoGoalExecutionConfiguration(subProject,"report-integration");
			if (!validateReportFormat(reportIntegrationConfigurationDom)) {
				throw new MojoFailureException(String.format(errorMessage, subProject.getName(), JACOCO_PLUGIN_NAME, "report-integration"));
			}
			reportIntegrationGoalOutputFiles.add(getCustomOutputDirectory(reportConfigurationDom).orElse(defaultOutputDirectory.resolve("jacoco-it").resolve(jacocoXML)));

			Xpp3Dom reportAggregateConfigurationDom = getJacocoGoalExecutionConfiguration(subProject,"report-aggregate");
			if (!validateReportFormat(reportAggregateConfigurationDom)) {
				throw new MojoFailureException(String.format(errorMessage, subProject.getName(), JACOCO_PLUGIN_NAME, "report-aggregate"));
			}
			reportAggregateGoalOutputFiles.add(getCustomOutputDirectory(reportConfigurationDom).orElse(defaultOutputDirectory.resolve("jacoco-aggregate").resolve(jacocoXML)));
		}
	}

	private void uploadCoverageReports() throws IOException {
		uploadCoverage(reportGoalOutputFiles, unitTestPartition);
		uploadCoverage(reportIntegrationGoalOutputFiles, integrationTestPartition);
		uploadCoverage(reportAggregateGoalOutputFiles, aggregatedTestPartition);
	}

	private void uploadCoverage(List<Path> reportOutputFiles, String partition) throws IOException {
		List<File> reports = new ArrayList<>();
		for (Path reportPath : reportOutputFiles) {
			File report = reportPath.toFile();
			if (!report.exists()) {
				getLog().debug(String.format("Cannot find %s, skipping...", report.getAbsolutePath()));
				continue;
			}
			if (!report.canRead()) {
				getLog().warn(String.format("Cannot read %s, skipping!", report.getAbsolutePath()));
				continue;
			}
			reports.add(report);
		}
		if (!reports.isEmpty()) {
			getLog().info(String.format("Uploading %d Jacoco report for project %s to %s", reports.size(), projectId, partition));
			teamscaleClient.uploadReports(EReportFormat.JACOCO, reports, CommitDescriptor.parse(resolvedCommit), revision, partition, COVERAGE_UPLOAD_MESSAGE);
		} else {
			getLog().info(String.format("Found no valid reports for %s", partition));
		}
	}

	/**
	 * Validates that a configuration Dom is set up to generate XML reports
	 * @param configurationDom The configuration Dom of a goal execution
	 */
	private boolean validateReportFormat(Xpp3Dom configurationDom) {
		if (configurationDom == null || configurationDom.getChild("formats") == null) {
			return true;
		}
		for (Xpp3Dom format : configurationDom.getChild("formats").getChildren()) {
			if (format.getValue().equals("XML")) {
				return true;
			}
		}
		return false;
	}

	private Optional<Path> getCustomOutputDirectory(Xpp3Dom configurationDom) {
		if (configurationDom != null && configurationDom.getChild("outputDirectory") != null) {
			return Optional.of(Paths.get(configurationDom.getChild("outputDirectory").getValue()));
		}
		return Optional.empty();
	}

	private Xpp3Dom getJacocoGoalExecutionConfiguration(MavenProject project, String pluginGoal) {
		return super.getExecutionConfigurationDom(project, JACOCO_PLUGIN_NAME, pluginGoal);
	}
}
