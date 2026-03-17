# Deployment Guide

## Prerequisites

- Java 21 or later
- Kotlin 1.9.x
- Gradle 8.x
- Git

## Quick Start

### 1. Clone and Build
```bash
git clone <repository-url>
cd ${PROJECT_ROOT}
./gradlew build
```

### 2. Start Context Server
```bash
# Using PowerShell wrapper (recommended)
.\i2vision-context.ps1 -StartServer -Port 3001

# Or direct Gradle execution
.\gradlew :server:run --args="--port=3001 --project=."
```

### 3. Verify Installation
```bash
curl http://localhost:3001/context/health
# Should return: {"status":"healthy","service":"i2vision"}
```

---

## Configuration Options

### Server Configuration

**Environment Variables:**
- `I2VISION_PORT` - Server port (default: 3001)
- `I2VISION_PROJECT_PATH` - Project root path (default: current directory)
- `I2VISION_CACHE_TTL` - Context cache TTL in minutes (default: 5)

**Command Line Arguments:**
```bash
.\gradlew :server:run --args="--port=8080 --project=/path/to/project"
```

### Discovery Configuration

The system uses the semantic cache for context generation. Ensure discovery has been run:

```bash
# Run discovery for better context quality
.\scripts\tools\test-module-discovery.ps1
```

---

## Deployment Scenarios

### Local Development
```bash
# Start server for local development
.\i2vision-context.ps1 -StartServer

# Access from IDE or LLM tools
curl "http://localhost:3001/context/quick?q=src/main/kotlin/Application.kt"
```

### CI/CD Integration
```yaml
# GitHub Actions example
name: Context Server Test
on: [push, pull_request]

jobs:
  context-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
      - name: Build project
        run: ./gradlew build
      - name: Start context server
        run: |
          ./gradlew :server:run --args="--port=3001" &
          sleep 10
      - name: Test context API
        run: |
          curl http://localhost:3001/context/health
          curl "http://localhost:3001/context/project"
```

### Production Deployment

#### Docker Deployment
```dockerfile
FROM openjdk:21-jdk-slim

WORKDIR /app
COPY . .
RUN ./gradlew build

EXPOSE 3001
CMD ["./gradlew", ":server:run", "--args=--port=3001"]
```

#### Systemd Service
```ini
[Unit]
Description=i2vision Context Server
After=network.target

[Service]
Type=simple
User=i2vision
WorkingDirectory=${PROJECT_ROOT}
ExecStart=${PROJECT_ROOT}/gradlew :server:run --args="--port=3001"
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

### Cloud Deployment

#### AWS EC2
```bash
# Install dependencies
sudo yum update
sudo yum install -y java-21-openjdk

# Deploy application
git clone <repository-url>
cd ${PROJECT_ROOT}
./gradlew build

# Start service
nohup ./gradlew :server:run --args="--port=3001" > context-server.log 2>&1 &
```

#### Kubernetes
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: i2vision-server
spec:
  replicas: 2
  selector:
    matchLabels:
      app: i2vision-server
  template:
    metadata:
      labels:
        app: i2vision-server
    spec:
      containers:
      - name: i2vision-server
        image: i2vision-server:latest
        ports:
        - containerPort: 3001
        env:
        - name: I2VISION_PORT
          value: "3001"
---
apiVersion: v1
kind: Service
metadata:
  name: i2vision-service
spec:
  selector:
    app: i2vision-server
  ports:
  - protocol: TCP
    port: 80
    targetPort: 3001
  type: LoadBalancer
```

---

## Security Considerations

### Network Security
- **Local Development**: Server binds to localhost only
- **Production**: Use reverse proxy (nginx, Apache) for HTTPS
- **Firewall**: Restrict access to authorized clients only

### Authentication
Currently no authentication is implemented. For production:
- Add API key authentication
- Implement rate limiting
- Use HTTPS with proper certificates

### Data Security
- Context data contains code analysis - ensure secure transmission
- Cache files contain sensitive project information
- Consider data retention policies

---

## Monitoring and Logging

### Health Monitoring
```bash
# Basic health check
curl http://localhost:3001/context/health

# Detailed monitoring endpoint (if implemented)
curl http://localhost:3001/metrics
```

### Log Configuration
Default logging goes to console. For production, configure log files:

```properties
# logback-spring.xml
<configuration>
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/context-server.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/context-server.%d{yyyy-MM-dd}.gz</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="FILE" />
    </root>
</configuration>
```

---

## Troubleshooting

### Common Issues

#### Server Won't Start
```bash
# Check if port is already in use
netstat -an | grep :3001

# Use different port
.\gradlew :server:run --args="--port=3002"
```

#### Low Context Confidence
```bash
# Run discovery to improve context quality
.\scripts\tools\test-module-discovery.ps1

# Check semantic cache
ls .semantic-cache/
```

#### Performance Issues
```bash
# Increase JVM memory
export GRADLE_OPTS="-Xmx2g -Xms1g"

# Clear cache if needed
curl "http://localhost:3001/context/cache/invalidate"
```

### Log Analysis
```bash
# Check for errors
grep "ERROR" logs/context-server.log

# Monitor API requests
grep "GET /context" logs/context-server.log

# Performance monitoring
grep "took" logs/context-server.log
```

---

## Maintenance

### Regular Maintenance
- **Weekly**: Clear expired cache entries
- **Monthly**: Update discovery analysis
- **Quarterly**: Review and update configurations

### Cache Management
```bash
# Clear all cache
curl "http://localhost:3001/context/cache/invalidate"

# Clear specific pattern
curl "http://localhost:3001/context/cache/invalidate?pattern=orchestrator"
```

### Updates and Upgrades
```bash
# Update codebase
git pull origin main

# Rebuild
./gradlew clean build

# Restart service
# (use your deployment method's restart command)
```

---

For additional support, see the [API Reference](../reference/API_REFERENCE.md) and [Integration Guide](MCP_INTEGRATION.md).


