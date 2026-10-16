# ---- Build stage ----
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /build

# Cache dependency resolution separately from source
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# ---- Runtime stage ----
FROM tomcat:10.1-jre17-temurin

# Remove default Tomcat apps
RUN rm -rf /usr/local/tomcat/webapps/*

RUN sed -i 's#</Host>#  <Valve className="org.apache.catalina.valves.RemoteIpValve" remoteIpHeader="X-Forwarded-For" protocolHeader="X-Forwarded-Proto" protocolHeaderHttpsValue="https"/>\n      </Host>#' /usr/local/tomcat/conf/server.xml && grep -A2 -B2 "RemoteIpValve" /usr/local/tomcat/conf/server.xml

COPY --from=builder /build/target/FHIRServer.war /usr/local/tomcat/webapps/ROOT.war

# /config  — mount application.yaml and config.properties from host
# /data/ig — mount IG package (.tgz) files from host
VOLUME ["/config", "/data/ig"]

EXPOSE 8080

CMD ["catalina.sh", "run"]
