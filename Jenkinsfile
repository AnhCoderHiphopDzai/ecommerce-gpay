pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
    }

    environment {
        DEPLOY_HOST = '192.168.179.129'
        FRONTEND_PORT = '8088'
        BACKEND_PORT = '8081'
        COMPOSE_PROJECT_NAME = 'demo1'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build images') {
            steps {
                sh 'docker compose build'
            }
        }

        stage('Approve deployment') {
            steps {
                input message: 'Cho phép triển khai phiên bản này lên Ubuntu VM?', ok: 'Deploy'
            }
        }

        stage('Deploy') {
            steps {
                withCredentials([
                    string(credentialsId: 'APP_JWT_SECRET', variable: 'APP_JWT_SECRET'),
                    string(credentialsId: 'SPRING_DATASOURCE_PASSWORD', variable: 'SPRING_DATASOURCE_PASSWORD'),
                    string(credentialsId: 'GPAY_CLIENT_SECRET', variable: 'GPAY_CLIENT_SECRET'),
                    string(credentialsId: 'GPAY_PRIVATE_KEY', variable: 'GPAY_PRIVATE_KEY'),
                    string(credentialsId: 'GPAY_CERTIFICATE', variable: 'GPAY_CERTIFICATE')
                ]) {
                    sh 'docker compose up -d --remove-orphans'
                }
            }
        }
    }

    post {
        always {
            sh 'docker compose ps'
        }
    }
}
