#!/usr/bin/env groovy

/**
 * Jenkins Pipeline for NICE CXone Healthcare Telephony Integration
 *
 * This pipeline handles the CI/CD process for healthcare telephony integrations:
 * - Validates and lints CXone script files
 * - Runs unit and integration tests
 * - Performs security scanning and compliance checks
 * - Deploys to development, staging, and production environments
 */

pipeline {
    agent {
        kubernetes {
            yaml """
apiVersion: v1
kind: Pod
metadata:
  labels:
    app: cxone-healthcare-pipeline
spec:
  containers:
  - name: java
    image: openjdk:11
    command:
    - cat
    tty: true
  - name: node
    image: node:16
    command:
    - cat
    tty: true
  - name: cxone-cli
    image: nice/cxone-cli:latest
    command:
    - cat
    tty: true
  - name: sonar
    image: sonarsource/sonar-scanner-cli:4.6
    command:
    - cat
    tty: true
  - name: security
    image: owasp/dependency-check:latest
    command:
    - cat
    tty: true
  volumeMounts:
  - name: shared-data
    mountPath: /shared-data
  volumes:
  - name: shared-data
    emptyDir: {}
"""
        }
    }
    
    environment {
        CXONE_API_KEY = credentials('cxone-api-key')
        CXONE_ENV_DEV = 'healthcare-dev'
        CXONE_ENV_STAGING = 'healthcare-staging'
        CXONE_ENV_PROD = 'healthcare-prod'
        
        HIPAA_COMPLIANCE_ENABLED = 'true'
        HIPAA_COMPLIANCE_LEVEL = 'strict'
    }
    
    parameters {
        choice(name: 'DEPLOY_ENV', choices: ['dev', 'staging', 'prod'], description: 'Deployment Environment')
        booleanParam(name: 'RUN_SECURITY_SCAN', defaultValue: true, description: 'Run Security Scan')
        booleanParam(name: 'RUN_COMPLIANCE_CHECK', defaultValue: true, description: 'Run HIPAA Compliance Check')
        string(name: 'VERSION_TAG', defaultValue: '', description: 'Version Tag (leave empty for auto-generation)')
    }
    
    stages {
        stage('Initialize') {
            steps {
                container('node') {
                    sh 'npm install'
                }
                
                script {
                    if (params.VERSION_TAG == '') {
                        env.VERSION = "v${new Date().format('yyyy.MM.dd')}-${env.BUILD_NUMBER}"
                    } else {
                        env.VERSION = params.VERSION_TAG
                    }
                    
                    echo "Building version: ${env.VERSION}"
                }
            }
        }
        
        stage('Lint & Validate CXone Scripts') {
            steps {
                container('cxone-cli') {
                    sh '''
                        echo "Linting CXone scripts..."
                        cxone-cli lint --dir ./scripts --format json > lint-results.json
                        
                        echo "Validating IVR flow logic..."
                        cxone-cli validate --dir ./scripts/ivr --format json > validation-results.json
                        
                        echo "Checking for deprecated functions..."
                        cxone-cli check-deprecated --dir ./scripts
                    '''
                    
                    script {
                        def lintResults = readJSON file: 'lint-results.json'
                        def validationResults = readJSON file: 'validation-results.json'
                        
                        if (lintResults.errorCount > 0) {
                            error "Linting failed with ${lintResults.errorCount} errors"
                        }
                        
                        if (validationResults.errors.size() > 0) {
                            error "Script validation failed with ${validationResults.errors.size()} errors"
                        }
                    }
                }
            }
        }
        
        stage('Unit Tests') {
            steps {
                container('node') {
                    sh 'npm test'
                }
                
                container('java') {
                    sh './gradlew test'
                }
            }
            post {
                always {
                    junit '**/test-results/*.xml'
                }
            }
        }
        
        stage('Integration Tests') {
            steps {
                container('java') {
                    sh './gradlew integrationTest'
                }
                
                container('cxone-cli') {
                    sh '''
                        echo "Running CXone script integration tests..."
                        cxone-cli test-integration --config ./test-config/integration.json
                    '''
                }
            }
            post {
                always {
                    junit '**/integration-test-results/*.xml'
                }
            }
        }
        
        stage('HIPAA Compliance Check') {
            when {
                expression { params.RUN_COMPLIANCE_CHECK == true }
            }
            steps {
                container('cxone-cli') {
                    sh '''
                        echo "Running HIPAA compliance checks..."
                        cxone-cli compliance-check --type hipaa --level ${HIPAA_COMPLIANCE_LEVEL} --dir ./scripts
                    '''
                }
                
                script {
                    def complianceResults = sh(script: '''
                        cat compliance-results.json
                    ''', returnStdout: true).trim()
                    
                    def complianceJson = readJSON text: complianceResults
                    
                    if (complianceJson.violations.size() > 0) {
                        echo "HIPAA compliance violations found: ${complianceJson.violations.size()}"
                        
                        if (complianceJson.critical > 0) {
                            error "Critical HIPAA compliance violations found, aborting pipeline"
                        } else {
                            echo "Non-critical HIPAA compliance issues found, continuing with warnings"
                        }
                    } else {
                        echo "No HIPAA compliance violations found"
                    }
                }
            }
        }
        
        stage('Security Scan') {
            when {
                expression { params.RUN_SECURITY_SCAN == true }
            }
            steps {
                container('security') {
                    sh '''
                        dependency-check --project "Healthcare Telephony" --scan ./ --out security-reports --format ALL
                    '''
                }
                
                container('sonar') {
                    sh '''
                        sonar-scanner \
                          -Dsonar.projectKey=healthcare-telephony \
                          -Dsonar.sources=. \
                          -Dsonar.host.url=${SONAR_HOST_URL} \
                          -Dsonar.login=${SONAR_AUTH_TOKEN}
                    '''
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: 'security-reports/**', allowEmptyArchive: true
                }
            }
        }
        
        stage('Build & Package') {
            steps {
                container('node') {
                    sh '''
                        echo "Building CXone packages..."
                        npm run build
                    '''
                }
                
                container('java') {
                    sh '''
                        echo "Building Java integration components..."
                        ./gradlew build -x test
                    '''
                }
                
                container('cxone-cli') {
                    sh '''
                        echo "Packaging CXone scripts and configurations..."
                        cxone-cli package --dir ./scripts --output ./packages
                    '''
                }
                
                archiveArtifacts artifacts: 'packages/**', allowEmptyArchive: false
            }
        }
        
        stage('Deploy to Development') {
            when {
                expression { params.DEPLOY_ENV == 'dev' || params.DEPLOY_ENV == 'staging' || params.DEPLOY_ENV == 'prod' }
            }
            steps {
                container('cxone-cli') {
                    sh '''
                        echo "Deploying to development environment..."
                        cxone-cli deploy --package ./packages/healthcare-package.zip --env ${CXONE_ENV_DEV} --key ${CXONE_API_KEY}
                    '''
                }
            }
        }
        
        stage('Deploy to Staging') {
            when {
                expression { params.DEPLOY_ENV == 'staging' || params.DEPLOY_ENV == 'prod' }
            }
            steps {
                timeout(time: 1, unit: 'HOURS') {
                    input message: 'Approve deployment to staging?', ok: 'Deploy'
                }
                
                container('cxone-cli') {
                    sh '''
                        echo "Deploying to staging environment..."
                        cxone-cli deploy --package ./packages/healthcare-package.zip --env ${CXONE_ENV_STAGING} --key ${CXONE_API_KEY}
                    '''
                }
                
                script {
                    def testResults = sh(script: '''
                        cxone-cli test-smoke --env ${CXONE_ENV_STAGING} --config ./test-config/smoke.json --format json
                    ''', returnStdout: true).trim()
                    
                    def testJson = readJSON text: testResults
                    
                    if (testJson.failed > 0) {
                        error "Smoke tests failed in staging environment"
                    }
                }
            }
        }
        
        stage('Deploy to Production') {
            when {
                expression { params.DEPLOY_ENV == 'prod' }
            }
            steps {
                timeout(time: 24, unit: 'HOURS') {
                    input message: 'Approve deployment to production?', ok: 'Deploy'
                }
                
                container('cxone-cli') {
                    sh '''
                        echo "Deploying to production environment..."
                        cxone-cli deploy --package ./packages/healthcare-package.zip --env ${CXONE_ENV_PROD} --key ${CXONE_API_KEY}
                    '''
                }
                
                script {
                    def testResults = sh(script: '''
                        cxone-cli test-smoke --env ${CXONE_ENV_PROD} --config ./test-config/smoke.json --format json
                    ''', returnStdout: true).trim()
                    
                    def testJson = readJSON text: testResults
                    
                    if (testJson.failed > 0) {
                        error "Smoke tests failed in production environment"
                    }
                }
            }
        }
    }
    
    post {
        always {
            echo "Pipeline completed with result: ${currentBuild.result}"
            
            archiveArtifacts artifacts: 'lint-results.json, validation-results.json, compliance-results.json', allowEmptyArchive: true
            
            script {
                if (currentBuild.result == 'FAILURE' || currentBuild.result == 'UNSTABLE') {
                    // Send notifications on failure
                    echo "Sending failure notifications..."
                    
                    emailext (
                        subject: "Build Failed: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                        body: """
                            The pipeline has failed. Check the logs for details.
                            
                            Build URL: ${env.BUILD_URL}
                        """,
                        recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']]
                    )
                }
                
                if (currentBuild.result == 'SUCCESS' && params.DEPLOY_ENV == 'prod') {
                    // Send success notifications for production deployments
                    echo "Sending production deployment success notifications..."
                    
                    emailext (
                        subject: "Production Deployment Successful: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                        body: """
                            The pipeline has successfully deployed to production.
                            
                            Version: ${env.VERSION}
                            Build URL: ${env.BUILD_URL}
                        """,
                        recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']]
                    )
                }
            }
        }
        
        cleanup {
            cleanWs()
        }
    }
}
