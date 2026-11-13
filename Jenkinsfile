pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        skipDefaultCheckout(true)
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '20'))
        timeout(time: 50, unit: 'MINUTES')
    }

    // GitHub webhook là lựa chọn tốt hơn khi đã cấu hình. Polling này giữ cơ chế tự chạy
    // mỗi hai phút hiện có của demo trong trường hợp webhook chưa được mở từ Internet.
    triggers {
        pollSCM('H/2 * * * *')
    }

    parameters {
        string(name: 'DEPLOY_HOST', defaultValue: '192.168.179.129', trim: true,
               description: 'Ubuntu VM IPv4 address or DNS hostname, without http:// or a port. Images deploy on the local Docker daemon.')
    }

    environment {
        DEPLOY_HOST = "${params.DEPLOY_HOST}"
        DEPLOY_PROJECT = 'demo1'
        FRONTEND_PORT = '8088'
        BACKEND_PORT = '8081'
        CI_PROJECT = "demo1-ci-${BUILD_NUMBER}"
        CI_DB_PASSWORD = 'ci-test-only-password'
        CI_JWT_SECRET = 'ci-test-jwt-secret-not-for-deployment-1234567890'
        ARTIFACT_DIR = 'artifacts'
        BACKEND_REPOSITORY = 'demo1-backend'
        FRONTEND_REPOSITORY = 'demo1-frontend'
    }
    stages {
    stage('Checkout') {
        steps {
            script {
                // Mutable flags must not be declared in environment: its withEnv scope
                // would mask later env assignments and suppress the rollback path.
                env.POST_DEPLOY_SMOKE_FAILED = 'false'
                env.HAS_STABLE_RELEASE = 'false'
            }
            checkout scm
            sh '''
                set -eu
                rm -rf demo/target frontend/test-results frontend/coverage artifacts
            '''
            script {
                env.GIT_SHA = sh(script: 'git rev-parse --verify HEAD', returnStdout: true).trim()
                env.IMAGE_TAG = "build-${env.BUILD_NUMBER}-${env.GIT_SHA.take(12)}"
                env.BACKEND_IMAGE = "${env.BACKEND_REPOSITORY}:${env.IMAGE_TAG}"
                env.FRONTEND_IMAGE = "${env.FRONTEND_REPOSITORY}:${env.IMAGE_TAG}"
            }
        }
    }

        stage('Runner preflight') {
            steps {
                script {
                    if (!isUnix()) {
                        error 'This pipeline must run on a Linux Jenkins agent on the Ubuntu deployment VM.'
                    }
                }
                sh 'sh ci/preflight.sh'
            }
        }

        stage('Validate configuration') {
            steps {
                sh '''
                    set -eu
                    SPRING_DATASOURCE_PASSWORD="$CI_DB_PASSWORD" \
                    APP_JWT_SECRET="$CI_JWT_SECRET" \
                    GPAY_CLIENT_SECRET=ci-validation-client-secret \
                    GPAY_PRIVATE_KEY=ci-validation-private-key \
                    GPAY_CERTIFICATE=ci-validation-certificate \
                    docker compose -f compose.yaml config --quiet

                    CI_DB_PASSWORD="$CI_DB_PASSWORD" \
                    CI_JWT_SECRET="$CI_JWT_SECRET" \
                    BACKEND_IMAGE=demo1-backend:validation \
                    FRONTEND_IMAGE=demo1-frontend:validation \
                    docker compose -f compose.ci.yaml config --quiet
                '''
            }
        }

        stage('Frontend quality') {
            steps {
                sh '''
                    set -eu
                    docker run --rm \
                        --user "$(id -u):$(id -g)" \
                        -v "$WORKSPACE/frontend:/app" \
                        -w /app \
                        node:22-alpine \
                        sh -ec 'npm ci --cache /tmp/npm && npm run lint && mkdir -p test-results && npm run test:ci && npm run build'
                '''
            }
        }

        stage('Backend unit tests') {
            steps {
                sh '''
                    set -eu
                    cleanup() {
                        result=$?
                        trap - EXIT
                        docker compose -f compose.ci.yaml -p "$CI_PROJECT" down --volumes --remove-orphans || true
                        exit "$result"
                    }
                    trap cleanup EXIT
                    export HOST_UID="$(id -u)" HOST_GID="$(id -g)"
                    docker compose -f compose.ci.yaml -p "$CI_PROJECT" run --rm unit-test
                '''
            }
        }

        stage('Backend integration and API tests') {
            steps {
                sh '''
                    set -eu
                    cleanup() {
                        result=$?
                        trap - EXIT
                        sh ci/collect-compose-diagnostics.sh compose.ci.yaml "$CI_PROJECT" "$ARTIFACT_DIR/ci-stack" || true
                        docker compose -f compose.ci.yaml -p "$CI_PROJECT" down --volumes --remove-orphans || true
                        exit "$result"
                    }
                    trap cleanup EXIT

                    export CI_DB_PASSWORD CI_JWT_SECRET
                    export HOST_UID="$(id -u)" HOST_GID="$(id -g)"
                    docker compose -f compose.ci.yaml -p "$CI_PROJECT" up -d db redis
                    docker compose -f compose.ci.yaml -p "$CI_PROJECT" run --rm integration-test
                '''
            }
        }

        stage('Build immutable candidate images') {
            steps {
                sh '''
                    set -eu
                    docker build --pull --tag "$BACKEND_IMAGE" demo
                    docker build --pull \
                        --build-arg "VITE_API_BASE_URL=http://${DEPLOY_HOST}:${BACKEND_PORT}" \
                        --tag "$FRONTEND_IMAGE" frontend
                '''
            }
        }

        stage('Candidate smoke tests') {
            steps {
                sh '''
                    set -eu
                    cleanup() {
                        result=$?
                        trap - EXIT
                        sh ci/collect-compose-diagnostics.sh compose.ci.yaml "$CI_PROJECT" "$ARTIFACT_DIR/candidate-stack" || true
                        docker compose -f compose.ci.yaml -p "$CI_PROJECT" down --volumes --remove-orphans || true
                        exit "$result"
                    }
                    trap cleanup EXIT

                    export CI_DB_PASSWORD CI_JWT_SECRET BACKEND_IMAGE FRONTEND_IMAGE
                    docker compose -f compose.ci.yaml -p "$CI_PROJECT" up -d db redis backend frontend
                    docker compose -f compose.ci.yaml -p "$CI_PROJECT" run --rm smoke
                '''
            }
        }

        stage('Preserve rollback baseline') {
            steps {
                script {
                    sh '''
                        set -eu
                        if ! docker image inspect "${BACKEND_REPOSITORY}:stable" >/dev/null 2>&1 \
                            && docker image inspect "${BACKEND_REPOSITORY}:latest" >/dev/null 2>&1; then
                            docker tag "${BACKEND_REPOSITORY}:latest" "${BACKEND_REPOSITORY}:stable"
                        fi
                        if ! docker image inspect "${FRONTEND_REPOSITORY}:stable" >/dev/null 2>&1 \
                            && docker image inspect "${FRONTEND_REPOSITORY}:latest" >/dev/null 2>&1; then
                            docker tag "${FRONTEND_REPOSITORY}:latest" "${FRONTEND_REPOSITORY}:stable"
                        fi
                    '''
                    env.HAS_STABLE_RELEASE = sh(
                            script: '''
                                if docker image inspect "${BACKEND_REPOSITORY}:stable" >/dev/null 2>&1 \
                                    && docker image inspect "${FRONTEND_REPOSITORY}:stable" >/dev/null 2>&1; then
                                    printf true
                                else
                                    printf false
                                fi
                            ''',
                            returnStdout: true
                    ).trim()
                    if (env.HAS_STABLE_RELEASE == 'true') {
                        echo 'A stable release is available for a manually approved rollback.'
                    } else {
                        echo 'No complete stable release exists yet; a failed first deployment cannot be rolled back automatically.'
                    }
                }
            }
        }

        stage('Approve deployment') {
            steps {
                script {
                    timeout(time: 20, unit: 'MINUTES') {
                        input message: "Triển khai candidate ${env.IMAGE_TAG} lên Ubuntu VM? Tất cả cổng kiểm tra CI đã đạt.",
                              ok: 'Deploy'
                    }
                }
            }
        }

        stage('Deploy candidate') {
            steps {
                withCredentials([
                    string(credentialsId: 'APP_JWT_SECRET', variable: 'APP_JWT_SECRET'),
                    string(credentialsId: 'SPRING_DATASOURCE_PASSWORD', variable: 'SPRING_DATASOURCE_PASSWORD'),
                    string(credentialsId: 'GPAY_CLIENT_SECRET', variable: 'GPAY_CLIENT_SECRET'),
                    string(credentialsId: 'GPAY_PRIVATE_KEY', variable: 'GPAY_PRIVATE_KEY'),
                    string(credentialsId: 'GPAY_CERTIFICATE', variable: 'GPAY_CERTIFICATE')
                ]) {
                    sh '''
                        set -eu
                        export BACKEND_IMAGE FRONTEND_IMAGE
                        docker compose -f compose.yaml -p "$DEPLOY_PROJECT" up -d --remove-orphans
                    '''
                }
            }
        }

        stage('Post-deploy smoke tests') {
            steps {
                script {
                    try {
                        sh '''
                            set -eu
                            BACKEND_URL="http://127.0.0.1:${BACKEND_PORT}" \
                            FRONTEND_URL="http://127.0.0.1:${FRONTEND_PORT}" \
                            FRONTEND_ORIGIN="http://${DEPLOY_HOST}:${FRONTEND_PORT}" \
                            sh ci/smoke-deployed.sh
                        '''
                    } catch (Exception ignored) {
                        env.POST_DEPLOY_SMOKE_FAILED = 'true'
                        currentBuild.result = 'FAILURE'
                        echo 'Post-deploy smoke tests failed. Deployment diagnostics are being archived; no automatic rollback will occur.'
                    }
                }
            }
        }

        stage('Approve rollback') {
            when {
                expression { env.POST_DEPLOY_SMOKE_FAILED == 'true' && env.HAS_STABLE_RELEASE == 'true' }
            }
            steps {
                script {
                    timeout(time: 10, unit: 'MINUTES') {
                        input message: 'Smoke test sau deploy thất bại. Khôi phục image stable gần nhất?', ok: 'Rollback'
                    }
                }
            }
        }

        stage('Rollback stable images') {
            when {
                expression { env.POST_DEPLOY_SMOKE_FAILED == 'true' && env.HAS_STABLE_RELEASE == 'true' }
            }
            steps {
                withCredentials([
                    string(credentialsId: 'APP_JWT_SECRET', variable: 'APP_JWT_SECRET'),
                    string(credentialsId: 'SPRING_DATASOURCE_PASSWORD', variable: 'SPRING_DATASOURCE_PASSWORD'),
                    string(credentialsId: 'GPAY_CLIENT_SECRET', variable: 'GPAY_CLIENT_SECRET'),
                    string(credentialsId: 'GPAY_PRIVATE_KEY', variable: 'GPAY_PRIVATE_KEY'),
                    string(credentialsId: 'GPAY_CERTIFICATE', variable: 'GPAY_CERTIFICATE')
                ]) {
                    sh '''
                        set -eu
                        docker image inspect "${BACKEND_REPOSITORY}:stable" >/dev/null
                        docker image inspect "${FRONTEND_REPOSITORY}:stable" >/dev/null
                        BACKEND_IMAGE="${BACKEND_REPOSITORY}:stable" \
                        FRONTEND_IMAGE="${FRONTEND_REPOSITORY}:stable" \
                        docker compose -f compose.yaml -p "$DEPLOY_PROJECT" up -d --remove-orphans
                        BACKEND_URL="http://127.0.0.1:${BACKEND_PORT}" \
                        FRONTEND_URL="http://127.0.0.1:${FRONTEND_PORT}" \
                        FRONTEND_ORIGIN="http://${DEPLOY_HOST}:${FRONTEND_PORT}" \
                        sh ci/smoke-deployed.sh
                    '''
                }
            }
        }

        stage('No rollback baseline') {
            when {
                expression { env.POST_DEPLOY_SMOKE_FAILED == 'true' && env.HAS_STABLE_RELEASE != 'true' }
            }
            steps {
                echo 'No stable image pair is available. Do not continue the release; recover manually from a known-good backup or image.'
            }
        }

        stage('Mark release stable') {
            when {
                expression { env.POST_DEPLOY_SMOKE_FAILED != 'true' }
            }
            steps {
                sh '''
                    set -eu
                    docker tag "$BACKEND_IMAGE" "${BACKEND_REPOSITORY}:stable"
                    docker tag "$FRONTEND_IMAGE" "${FRONTEND_REPOSITORY}:stable"
                '''
            }
        }
    }

    post {
        always {
            sh '''
                sh ci/collect-compose-diagnostics.sh compose.ci.yaml "$CI_PROJECT" "$ARTIFACT_DIR/ci-final-stack" || true
                docker compose -f compose.ci.yaml -p "$CI_PROJECT" down --volumes --remove-orphans || true
                sh ci/collect-compose-diagnostics.sh compose.yaml "$DEPLOY_PROJECT" "$ARTIFACT_DIR/deploy-stack" || true
                docker ps -a --filter "label=com.docker.compose.project=${DEPLOY_PROJECT}" || true
            '''
            junit allowEmptyResults: true, testResults: 'demo/target/surefire-reports/*.xml,frontend/test-results/*.xml'
            archiveArtifacts allowEmptyArchive: true, artifacts: 'artifacts/**,frontend/coverage/**,demo/target/site/jacoco/**'
        }
        failure {
            script {
                if (env.POST_DEPLOY_SMOKE_FAILED == 'true') {
                    echo 'The candidate failed smoke tests after deployment. Review archived diagnostics and either complete the approved rollback or recover from the known-good release.'
                } else {
                    echo 'CI stopped before approval when a pre-deploy quality gate failed. Review the failed stage and archived diagnostics; fix through a new commit, then rerun the affected checks.'
                }
            }
        }
    }
}
