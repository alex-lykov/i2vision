/** @type {import('ts-jest').JestConfigWithTsJest} */
module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  roots: ['<rootDir>/src'],
  testMatch: [
    '**/__tests__/**/*.test.ts'
  ],
  moduleFileExtensions: ['ts', 'tsx', 'js', 'jsx', 'json', 'node'],
  transform: {
    '^.+\\.tsx?$': ['ts-jest', {
      tsconfig: 'tsconfig.json'
    }]
  },
  // Exclude test files that need VSCode extension host or Mocha
  testPathIgnorePatterns: [
    '/node_modules/',
    '/out/',
    '/src/test/',  // Mocha tests that need VSCode runtime
    '/src/agent/__tests__/'  // Mocha-based tests using suite()
  ]
};
