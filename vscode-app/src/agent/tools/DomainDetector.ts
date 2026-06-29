/**
 * DomainDetector - Architecture-aware domain detection for user queries
 * 
 * Detects whether a user's task is about frontend, backend, or shared code
 * by analyzing:
 * 1. Project architecture (if available from discovery cache)
 * 2. Module structure and technologies
 * 3. Keyword-based fallback
 * 
 * This guides the agent to explore the right directories first.
 */

import * as fs from 'fs';
import * as path from 'path';

export type ModuleDomain = 'frontend' | 'backend' | 'shared' | 'infrastructure' | 'unknown';

export interface ModuleInfo {
    name: string;
    path: string;
    domain: ModuleDomain;
    technologies: string[];
    sourceDirectories: string[];
}

export interface ProjectArchitecture {
    moduleStructure: {
        modules: ModuleInfo[];
    };
    technologyStack?: {
        frontend?: string[];
        backend?: string[];
        shared?: string[];
    };
}

export interface DomainResolution {
    primaryDomain: ModuleDomain;
    confidence: number;
    suggestedDirectories: string[];
    relevantModules: string[];
    matchingTechnologies: string[];
    rationale: string;
}

/**
 * DomainDetector - detects frontend/backend/shared domain from user queries
 */
export class DomainDetector {
    private architecture: ProjectArchitecture | null = null;
    private workspaceRoot?: string;

    /**
     * Initialize domain detector with workspace architecture
     */
    async initialize(workspaceRoot: string): Promise<void> {
        this.workspaceRoot = workspaceRoot;
        
        // Try loading from discovery cache
        const cachePath = path.join(workspaceRoot, '.vision-ai', 'cache', 'architecture.json');
        if (fs.existsSync(cachePath)) {
            try {
                const cacheContent = fs.readFileSync(cachePath, 'utf-8');
                this.architecture = JSON.parse(cacheContent) as ProjectArchitecture;
                console.log(`[DomainDetector] Loaded architecture from cache: ${this.architecture.moduleStructure.modules.length} modules`);
                return;
            } catch (e: any) {
                console.log(`[DomainDetector] Failed to load cache: ${e.message}`);
            }
        }
        
        // Fallback: detect from file system
        this.architecture = await this.detectFromFileSystem(workspaceRoot);
        console.log(`[DomainDetector] Detected architecture from file system: ${this.architecture.moduleStructure.modules.length} modules`);
    }

    /**
     * Detect project architecture from file system
     */
    private async detectFromFileSystem(workspaceRoot: string): Promise<ProjectArchitecture> {
        const modules: ModuleInfo[] = [];
        
        // Common directory patterns
        const dirPatterns: Array<{ pattern: RegExp; domain: ModuleDomain; technologies: string[] }> = [
            { pattern: /frontend|client|ui|web|src/i, domain: 'frontend', technologies: ['React', 'TypeScript', 'Vite', 'Vue', 'Angular'] },
            { pattern: /server|backend|api|app\/server/i, domain: 'backend', technologies: ['Kotlin', 'Ktor', 'Spring', 'Java'] },
            { pattern: /shared|common|core/i, domain: 'shared', technologies: ['Kotlin Multiplatform'] },
            { pattern: /infra|docker|k8s|deployment/i, domain: 'infrastructure', technologies: ['Docker', 'Kubernetes'] }
        ];
        
        try {
            const entries = fs.readdirSync(workspaceRoot, { withFileTypes: true });
            
            for (const entry of entries) {
                if (entry.isDirectory() && !entry.name.startsWith('.') && entry.name !== 'node_modules' && entry.name !== 'build') {
                    const dirPath = path.join(workspaceRoot, entry.name);
                    
                    for (const { pattern, domain, technologies } of dirPatterns) {
                        if (pattern.test(entry.name)) {
                            const sourceDirs = this.findSourceDirectories(dirPath, domain);
                            
                            modules.push({
                                name: entry.name,
                                path: dirPath,
                                domain,
                                technologies,
                                sourceDirectories: sourceDirs
                            });
                            break;
                        }
                    }
                }
            }
        } catch (e: any) {
            console.log(`[DomainDetector] Error detecting from file system: ${e.message}`);
        }
        
        return {
            moduleStructure: { modules }
        };
    }

    /**
     * Find source directories within a module
     */
    private findSourceDirectories(modulePath: string, domain: ModuleDomain): string[] {
        const sourceDirs: string[] = [];
        
        const commonPatterns = domain === 'frontend' 
            ? ['src', 'src/components', 'src/views', 'src/pages', 'app']
            : ['src', 'src/main', 'src/main/kotlin', 'src/main/java'];
        
        for (const pattern of commonPatterns) {
            const testPath = path.join(modulePath, pattern);
            if (fs.existsSync(testPath)) {
                sourceDirs.push(path.relative(this.workspaceRoot || modulePath, testPath));
            }
        }
        
        return sourceDirs.length > 0 ? sourceDirs : [path.relative(this.workspaceRoot || modulePath, modulePath)];
    }

    /**
     * Resolve domain for a user query
     */
    resolveDomain(userInput: string): DomainResolution {
        if (!this.architecture) {
            return this.keywordFallback(userInput);
        }
        
        const input = userInput.toLowerCase();
        
        // Score each module based on technology and path matches
        const scores = this.architecture.moduleStructure.modules.map(module => {
            const techScore = module.technologies.filter(t => 
                input.includes(t.toLowerCase())
            ).length * 2;
            
            const nameScore = input.includes(module.name.toLowerCase()) ? 5 : 0;
            
            const sourceScore = module.sourceDirectories.filter(dir =>
                input.includes(dir.toLowerCase()) || input.includes(path.basename(dir).toLowerCase())
            ).length * 2;
            
            return { 
                module, 
                score: techScore + nameScore + sourceScore 
            };
        });
        
        const bestMatch = scores.sort((a, b) => b.score - a.score)[0];
        
        if (bestMatch && bestMatch.score > 0) {
            return {
                primaryDomain: bestMatch.module.domain,
                confidence: Math.min(bestMatch.score / 10, 1.0),
                suggestedDirectories: bestMatch.module.sourceDirectories,
                relevantModules: [bestMatch.module.name],
                matchingTechnologies: bestMatch.module.technologies.filter(t =>
                    input.includes(t.toLowerCase())
                ),
                rationale: `Matched module '${bestMatch.module.name}' (score: ${bestMatch.score})`
            };
        }
        
        return this.keywordFallback(userInput);
    }

    /**
     * Keyword-based fallback when architecture detection fails
     */
    private keywordFallback(userInput: string): DomainResolution {
        const input = userInput.toLowerCase();
        
        const frontendSignals = [
            'ui', 'editor', 'canvas', 'ruler', 'svg', 'css', 'style', 'component',
            'react', 'vue', 'angular', 'button', 'layout', 'render', 'browser',
            'pointer-events', 'onclick', 'div', 'span', 'classname', 'jsx', 'tsx',
            'frontend', 'web', 'client', 'html', 'dom'
        ];
        
        const backendSignals = [
            'api', 'endpoint', 'route', 'controller', 'service', 'repository',
            'database', 'query', 'schema', 'migration', 'gradle', 'maven',
            'server', 'port', 'bind', 'kotlin', 'java', 'ktor', 'spring',
            'backend', 'dto', 'entity', 'jpa', 'sql', 'rest'
        ];
        
        const frontendScore = frontendSignals.filter(s => input.includes(s)).length;
        const backendScore = backendSignals.filter(s => input.includes(s)).length;
        
        if (frontendScore > backendScore) {
            return {
                primaryDomain: 'frontend',
                confidence: Math.min(frontendScore / 5, 0.8),
                suggestedDirectories: ['frontend/src', 'frontend/src/components', 'client/src'],
                relevantModules: [],
                matchingTechnologies: frontendSignals.filter(s => input.includes(s)),
                rationale: 'Keyword-based detection: frontend signals detected'
            };
        }
        
        if (backendScore > frontendScore) {
            return {
                primaryDomain: 'backend',
                confidence: Math.min(backendScore / 5, 0.8),
                suggestedDirectories: ['app/server', 'backend/src', 'server/src'],
                relevantModules: [],
                matchingTechnologies: backendSignals.filter(s => input.includes(s)),
                rationale: 'Keyword-based detection: backend signals detected'
            };
        }
        
        return {
            primaryDomain: 'unknown',
            confidence: 0.0,
            suggestedDirectories: [],
            relevantModules: [],
            matchingTechnologies: [],
            rationale: 'Could not determine domain from keywords'
        };
    }

    /**
     * Get all suggested directories for a domain
     */
    getSuggestedDirectories(domain: ModuleDomain): string[] {
        if (!this.architecture) {
            return [];
        }
        
        return this.architecture.moduleStructure.modules
            .filter(m => m.domain === domain)
            .flatMap(m => m.sourceDirectories);
    }

    /**
     * Get architecture info for debugging
     */
    getArchitecture(): ProjectArchitecture | null {
        return this.architecture;
    }
}
