FROM esta.docker.bin.sbb.ch/esta/ubi9/java-21:latest

COPY target/tms-ssp-chat-*.jar /deploy/app.jar
ENV JAVA_APP_JAR="/deploy/app.jar"
ENV CUSTOM_PRE_OPTS="-XX:+CrashOnOutOfMemoryError -Dorg.jooq.no-tips=true -Dorg.jooq.no-logo=true -DInstanaIgnore=true"