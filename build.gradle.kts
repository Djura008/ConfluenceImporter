plugins {
    kotlin("jvm") version "2.1.20"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"
   
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.cdimascio:dotenv-kotlin:6.2.2")
    testImplementation(kotlin("test"))
    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.14")
    implementation("org.apache.poi:poi-ooxml:5.4.1")
    implementation("org.jsoup:jsoup:1.20.1")
    implementation("org.docx4j:docx4j-core:11.4.9")
    implementation("org.docx4j:docx4j-JAXB-ReferenceImpl:11.4.9")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}
application {
    mainClass.set("MainKt") // Замените на ваш основной класс
}