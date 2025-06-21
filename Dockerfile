FROM tomcat:9.0-jdk17
COPY target/* /usr/local/tomcat/webapps/ROOT.war

# Optional: Healthcheck
HEALTHCHECK CMD curl --fail http://localhost:8080/ || exit 1