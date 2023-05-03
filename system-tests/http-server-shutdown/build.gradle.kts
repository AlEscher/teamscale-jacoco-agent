plugins {
    id("com.teamscale.system-test-convention")
    id("com.teamscale.coverage")
}

tasks.test {
    environment("AGENT_JAR", agentJar)
    val sampleJar = project(":sample-app").tasks["jar"].outputs.files.singleFile
    environment("SAMPLE_JAR", sampleJar)
    dependsOn(":sample-app:assemble")
}
