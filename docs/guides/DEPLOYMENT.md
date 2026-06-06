# Deployment Guide

## Prerequisites

- Java 21 or later
- Kotlin 1.9.x
- Gradle 8.x
- Git
- **Ollama** (for LLM functionality) - See [Ollama Integration Guide](ollama-integration.md)

## Quick Start

### 1. Clone and Build

```bash
git clone <repository-url>
cd ${PROJECT_ROOT}
./gradlew build
```

### 2. Setup Ollama (Required for LLM Features)

```bash
# Install Ollama from https://ollama.com

# Start Ollama server
ollama serve

# Pull a local model (recommended for development)
ollama pull llama3.2:3b

# Or register a cloud model
ollama pull minimax-m2.1:cloud

# Verify Ollama is running
ollama ls
```

### 3. Start Context Server

```bash
# Using PowerShell wrapper (recommended)
.\i2vision-context.ps1 -StartServer -Port 3001

# Or direct Gradle execution
.\gradlew :server:run --args="--port=3001 --project=."
```

### 4. Verify Installation

```bash
# Check context server health
curl http://localhost:3001/context/health
# Should return: {"status":"healthy","service":"i2vision"}

# Check Ollama connectivity
ollama run llama3.2:3b "Hello"
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

### LLM Configuration

Configure LLM provider in `.vision-ai/config.yml`:

```yaml
# .vision-ai/config.yml

llm:
  provider: ollama  # Primary provider
  
  ollama:
    base_url: http://localhost:11434
    model: llama3.2:3b  # or minimax-m2.1:cloud for cloud models
    
    # Model parameters
    temperature: 0.7
    topP: 0.9
    topK: 40
    maxTokens: 2048
    contextLength: 32768
```

See [Ollama Integration Guide](ollama-integration.md) for detailed configuration options.

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
# Start Ollama server (if not already running)
ollama serve

# Start context server for local development
.\i2vision-context.ps1 -StartServer

# Access from IDE or LLM tools
curl "http://localhost:3001/context/quick?q=src/main/kotlin/Application.kt"

# Use VSCode extension with Ollama provider
# Select: Provider=Ollama, Model=llama3.2:3b
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
      
      - name: Install Ollama
        run: |
          curl -fsSL https://ollama.com/install.sh | sh
          ollama pull llama3.2:3b
      
      - name: Build project
        run: ./gradlew build
      
      - name: Start Ollama server
        run: |
          ollama serve &
          sleep 5
      
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

# Install Ollama
RUN curl -fsSL https://ollama.com/install.sh | sh

WORKDIR /app
COPY . .
RUN ./gradlew build

EXPOSE 3001 3000
CMD ["sh", "-c", "ollama serve & ./gradlew :server:run --args=--port=3001"]
```

#### Docker Compose

```yaml
version: '3.8'
services:
  ollama:
    image: ollama/ollama:latest
    ports:
      - "11434:11434"
    volumes:
      - ollama_data:/root/.ollama
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: all
              capabilities: [gpu]
  
  i2vision-server:
    build: .
    ports:
      - "3001:3001"
    environment:
      - OLLAMA_HOST=http://ollama:11434
    depends_on:
      - ollama
    volumes:
      - ./project:/app/project

volumes:
  ollama_data:
```

#### Systemd Service

```ini
[Unit]
Description=i2vision Context Server
After=network.target ollama.service

[Service]
Type=simple
User=i2vision
WorkingDirectory=${PROJECT_ROOT}
Environment="OLLAMA_HOST=http://localhost:11434"
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

# Install Ollama
curl -fsSL https://ollama.com/install.sh | sh

# Pull model
ollama pull llama3.2:3b

# Deploy application
git clone <repository-url>
cd ${PROJECT_ROOT}
./gradlew build

# Start services
ollama serve &
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
        - name: OLLAMA_HOST
          value: "http://ollama-service:11434"
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
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ollama
spec:
  replicas: 1
  selector:
    matchLabels:
      app: ollama
  template:
    metadata:
      labels:
        app: ollama
    spec:
      containers:
      - name: ollama
        image: ollama/ollama:latest
        ports:
        - containerPort: 11434
        resources:
          limits:
            nvidia.com/gpu: 1
---
apiVersion: v1
kind: Service
metadata:
  name: ollama-service
spec:
  selector:
    app: ollama
  ports:
  - protocol: TCP
    port: 11434
    targetPort: 11434
```

---

## Security Considerations

### Network Security

- **Local Development**: Ollama and context server bind to localhost only
- **Production**: Use reverse proxy (nginx, Apache) for HTTPS
- **Firewall**: Restrict access to authorized clients only
- **Ollama Cloud**: Use HTTPS (handled automatically)

### Authentication

Currently no authentication is implemented. For production:

- Add API key authentication
- Implement rate limiting
- Use HTTPS with proper certificates
- Secure Ollama API access

### Data Security

- Context data contains code analysis - ensure secure transmission
- Cache files contain sensitive project information
- Ollama cloud models transmit data to Ollama Cloud API
- Consider data retention policies

### Ollama-Specific Security

```bash
# Keep Ollama on localhost only (default)
# Don't expose port 11434 to public network

# For cloud models, data is transmitted to Ollama Cloud
# Review Ollama's privacy policy: https://ollama.com/privacy

# Use local models for sensitive code
ollama pull llama3.2:7b
```

---

## Monitoring and Logging

### Health Monitoring

```bash
# Check context server health
curl http://localhost:3001/context/health

# Check Ollama health
ollama list

# Detailed monitoring endpoint (if implemented)
curl http://localhost:3001/metrics
```

### Ollama Monitoring

```bash
# Check running models
ollama ps

# Monitor GPU usage (NVIDIA)
nvidia-smi

# Check Ollama logs
# Windows: Event Viewer → Applications
# Linux: journalctl -u ollama
```

### Log Configuration

Default logging goes to console. For production, configure log files:

```xml
<!-- logback-spring.xml -->
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

#### Ollama Server Won't Start

```bash
# Check if Ollama is installed
ollama --version

# Check if port is in use
netstat -an | grep :11434

# Restart Ollama
# Windows: Restart Ollama app from system tray
# macOS/Linux: ollama serve
```

#### Model Not Found

```bash
# Pull the model
ollama pull llama3.2:3b

# List available models
ollama ls
```

#### Context Server Won't Start

```bash
# Check if port is already in use
netstat -an | grep :3001

# Use different port
.\gradlew :server:run --args="--port=3002"
```

#### Low Context Confidence

```bash
# Run discovery to improve context quality
./gradlew :i2vision-cli:run --args="discover /path/to/project"

# Check semantic cache location
# Windows: %LOCALAPPDATA%\i2vision\cache\projects\<hash>\.semantic-cache
# macOS: ~/Library/Application Support/i2vision/cache/projects/<hash>/.semantic-cache
# Linux: ~/.i2vision/cache/projects/<hash>/.semantic-cache
```

#### Performance Issues

```bash
# Increase JVM memory
export GRADLE_OPTS="-Xmx2g -Xms1g"

# Clear cache if needed
curl "http://localhost:3001/context/cache/invalidate"

# Check Ollama model performance
# Use smaller model for faster responses
ollama pull gemma3:1b
```

#### Cloud Models Not Working

```bash
# Check internet connection
ping ollama.com

# Test cloud model
ollama run minimax-m2.1:cloud "Hello"

# Check Ollama Cloud account
# Visit https://ollama.com/account
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

- **Daily**: Check Ollama server status
- **Weekly**: Clear expired cache entries
- **Monthly**: Update discovery analysis and models
- **Quarterly**: Review and update configurations

### Cache Management

```bash
# Clear all cache
curl "http://localhost:3001/context/cache/invalidate"

# Clear specific pattern
curl "http://localhost:3001/context/cache/invalidate?pattern=orchestrator"
```

### Model Management

```bash
# Update models
ollama pull llama3.2:7b

# Remove unused models
ollama rm old-model-name

# List model disk usage
ollama du
```

### Updates and Upgrades

```bash
# Update codebase
git pull origin main

# Rebuild
./gradlew clean build

# Update Ollama
# Download latest from https://ollama.com

# Restart services
# (use your deployment method's restart command)
```

---

## Performance Optimization

### Local Model Optimization

```bash
# Enable GPU acceleration (Ollama auto-detects)
# Ensure NVIDIA drivers are installed

# Monitor GPU usage
nvidia-smi

# Use smaller models for faster responses
ollama pull gemma3:1b
```

### Cloud Model Optimization

```yaml
# Use appropriate timeouts for cloud models
llm:
  ollama:
    model: minimax-m2.1:cloud
    timeout: 120  # seconds
    maxTokens: 4096
```

### Context Server Optimization

```bash
# Increase JVM heap size
export GRADLE_OPTS="-Xmx4g -Xms2g"

# Adjust cache TTL
export I2VISION_CACHE_TTL=10
```

---

## Cost Considerations

### Local Models
- **Cost**: Free (your hardware)
- **Electricity**: ~10-50W during inference
- **Hardware**: GPU/CPU investment

### Cloud Models (via Ollama Cloud)
- **Cost**: Ollama Cloud credits (check current pricing)
- **Rate Limits**: Apply based on Ollama Cloud terms
- **Latency**: Network round-trip (50-500ms)

See [Ollama Integration Guide](ollama-integration.md) for detailed cost analysis.

---

## Related Documentation

- [Ollama Integration Guide](ollama-integration.md) - Complete Ollama setup and usage
- [VSCode Provider Selection](vscode-provider-model-selection.md) - VSCode extension configuration
- [API Reference](../reference/api.md) - HTTP endpoints and interfaces
- [MCP Integration](mcp-integration.md) - Model Context Protocol setup

---

**Last Updated:** 2026-06-06
