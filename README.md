```bash
microservices-webapp/
│   pom.xml                 ← parent POM
├── backend/
│   pom.xml                 ← JavaEE WAR module
│   src/main/java/...
│   src/main/webapp/WEB-INF/web.xml
├── frontend/
│   pom.xml                 ← Angular (contains npm buil install)
│   src/
│   ...
```



- On AFTER, install Blueocean plugins