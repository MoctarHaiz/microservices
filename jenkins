pipeline {
    agent any
    environment {
        DOCKER_IMAGE = "myorg/microservices-webapp:${env.BUILD_NUMBER}"
    }
    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }
        stage('Build & Package') {
            steps {
                sh 'mvn clean package'
            }
        }
        stage('Test (backend only)') {
            steps {
                dir('backend') {
                    sh 'mvn test'
                }
            }
        }
        stage('Docker Build') {
            steps {
                sh 'docker build -t $DOCKER_IMAGE .'
            }
        }
        stage('Push Image') {
            steps {
                withCredentials([string(credentialsId: 'dockerhub-password', variable: 'DOCKER_PASSWORD')]) {
                    sh ''
                    '
                    echo $DOCKER_PASSWORD | docker login - u myusername--password - stdin
                    docker push $DOCKER_IMAGE
                    docker logout
                        ''
                    '
                }
            }
        }
        stage('Deploy to Kubernetes') {
            steps {
                sh 'kubectl apply -f k8s-deployment.yaml'
            }
        }
    }
    post {
        always {
            archiveArtifacts artifacts: 'target/*.war', fingerprint: true
        }
    }
}