plugins {
    id("java")
}

group = "com.dacubeking"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // apache
    implementation ("org.apache.httpcomponents.core5:httpcore5:5.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

}

tasks.test {
    useJUnitPlatform()
}