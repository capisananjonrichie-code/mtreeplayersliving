# Developer tooling (NOT part of the plugin jar)

## run-core-tests.sh
Compiles and runs the unit tests for the Bukkit-free core WITHOUT Gradle or Paper, using JDK 21 plus
JUnit 5, Gson, sqlite-jdbc and slf4j jars. Edit the `LIB` variable if your jars live elsewhere
(on Ubuntu: `apt install junit5 libgoogle-gson-java libxerial-sqlite-jdbc-java libslf4j-java openjdk-21-jdk-headless`).

## stub-check.sh
Compiles ALL main sources against `api-stubs/` - hand-written signatures of the Bukkit/Paper API this plugin uses.
It catches typos, bad imports and type errors in our own code. It does NOT prove the stubs match real Paper
(they were written from the 1.21.11 Javadoc and from memory). The real check is `gradle build`.
