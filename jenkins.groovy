// ----------------------------------------------------------------------------------------------------------------------------- //
// --                                                        FUNCTIONS                                                        -- //
// ----------------------------------------------------------------------------------------------------------------------------- //
def String getDestinationEnvironment() {
    if (env.BRANCH_NAME == "master") {
        return "prod"
    } else if (env.BRANCH_NAME == "develop") {
        return "val"
    } else if (env.BRANCH_NAME == "retaval") {
        // Used for validating reta platform changes, deploys the retaval branch which needs to be synchronized with the production master branch
        return "retaval"
    } else {
        return "dev"
    }
}

def String getFlattenedBranchName() {
    return sh(returnStdout: true, script: "echo ${env.BRANCH_NAME} | tr '/' '-' | tr '_' '-' | tr -d '\n' |  tr '[:upper:]' '[:lower:]' ")
}

def String getPomVersion() {
    return sh(returnStdout: true, script: "cat pom.xml | grep -A20 '<project' | grep '<version>' | tr '>' '<' | cut -d'<' -f 3 | head -1 |tr -d '\n' |tr -d '\r'")
}

def String getCorrectedPomVersion() {
    version = getPomVersion()
    if (env.BRANCH_NAME == "master") {
        if (version.contains("SNAPSHOT") || version.contains("snapshot")) {
            sh "echo '!!!! Release version (master branch) should not have a snapshot tag !!!!'"
            sh "exit 1"
        }
    } else {
        CHECKSUM = sh(returnStdout: true, script: "echo -n ${BRANCH_NAME_FLATTENED} | cksum  | awk '{print \$1}' | tr -d '\n' ")
        if (version.contains("SNAPSHOT") || version.contains("snapshot")) {
            version = version.replace("-SNAPSHOT", "." + CHECKSUM + "." + DESTINATION_ENV + "-SNAPSHOT")
        } else {
            sh "echo '>>> Changing parent version in POM : All branches but master need to provide a snapshot version in the pom'"
            version = version + "." + CHECKSUM + "." + DESTINATION_ENV + "-SNAPSHOT"
        }
    }
    return version

}

// check out
def check_out() {
    // FIXME : correct certs should be added and used directly.
    sh 'git config --global http.sslVerify false'
    checkout scm
}

// ----------------------------------------------------------------------------------------------------------------------------- //
// --                                                        VARIABLES                                                        -- //
// ----------------------------------------------------------------------------------------------------------------------------- //
def JENKINS_WORKSPACE = "jenkins-public-workspace"
def NSPROD = "ccc-pan-prod"
def NSVAL = "ccc-pan-val"
def NSDEV = "ccc-pan-dev"
def NSretaVAL = "ccc-pan-retaval"
def NOT_USE_WORKSPACE = true // do not forgot to uncomment/comment persistentVolumeClaimWorkspaceVolume in each agent step
def CLEAN_WORKSPACE = false

// ----------------------------------------------------------------------------------------------------------------------------- //
// --                                                       PIPELINE                                                             //
// ----------------------------------------------------------------------------------------------------------------------------- //
pipeline {
    agent none
    options {
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '7'))
        parallelsAlwaysFailFast()
    }
    stages {

        stage('BUILD WEBAPP (npm)') {
            agent {
                kubernetes {
                    cloud "basic-cluster-prod"
                    // cf https://confluence.moctar.corp/display/I2G22IVPR/%5Breta%5D+Jenkins+slaves
                    // with node 14.19.1, npm 6.14.12, angular/cli and chrome dependencies installed. The default ".npmrc" is located in the HOME folder
                    inheritFrom '2.4_nodejs-14.19.1_chrome'
                    yamlMergeStrategy merge()
                    //workspaceVolume persistentVolumeClaimWorkspaceVolume(claimName: JENKINS_WORKSPACE)
                }
            }
            stages {

                stage("CHECK-OUT") {
                    steps {
                        script {
                            check_out()
                            DESTINATION_ENV = getDestinationEnvironment()
                            BRANCH_NAME_FLATTENED = getFlattenedBranchName()
                            PARENT_VERSION = getCorrectedPomVersion()
                            if (env.BRANCH_NAME == "master") {
                                panoraPREFIX = "panora/search"
                            } else if (env.BRANCH_NAME == "retaval") {
                                panoraPREFIX = "panora/search"
                            } else if (env.BRANCH_NAME == "develop") {
                                panoraPREFIX = "panora-val/search"
                            } else {
                                panoraPREFIX = "panora-dev/search-dev-${BRANCH_NAME_FLATTENED}"
                            }
                            BASE_HREF = "/${panoraPREFIX}/ui/"
                        }

                        sh "echo '================================================================='"
                        sh "echo '                      ${DESTINATION_ENV} mode'"
                        sh "echo '================================================================='"
                        sh "echo 'Current Branch name   : ${env.BRANCH_NAME}'"
                        sh "echo 'Program Version       : ${PARENT_VERSION}'"
                        sh "echo 'Flattened Branch name : ${BRANCH_NAME_FLATTENED}'"
                    }
                }

                stage("NPM INSTALL & BUILD") {
                    steps {
                        script {
                            sh "echo 'install NPM moctar'"
                            sh "cd webapp ; \
                                npm install ; "
                            if (false && env.BRANCH_NAME == "develop") {
                                // Activate this to temporarily wire the production onto the val marklogic instance so that there's 
                                // no service interruption for users while we reinject all the data in the prod mkl instance
                                sh "cd webapp; npm run build -- --configuration=prod --aot=true --output-hashing=all --base-href=${BASE_HREF}|| ( cat /tmp/ng-*/angular-errors.log && exit 1)"
                            } else {
                                sh "cd webapp; npm run build -- --configuration=${DESTINATION_ENV} --aot=true --output-hashing=all --base-href=${BASE_HREF}|| ( cat /tmp/ng-*/angular-errors.log && exit 1)"
                            }
                        }
                    }
                }

                stage("NPM RUN UNIT TESTS") {
                    steps {
                        sh "cd webapp; npm run test --browsers=ChromeHeadless --codeCoverage=true --watch=false"
                        // stack front end reta unit test in order to have coverage report
                        script {
                            sh "mkdir -p webapp/target; cp -r webapp/dist/pan-ui webapp/target/pan-webapp"
                            if (NOT_USE_WORKSPACE == true) {
                                stash includes: "webapp/target/", name: "webapp-target"
                            }

                        }
                    }
                }
            }
        }

        stage('BUILD BACKEND (mvn)') {
            agent {
                kubernetes {
                    cloud "basic-cluster-prod"
                    inheritFrom '2.4_mvn-3.8.6'
                    yamlMergeStrategy merge()
                    //workspaceVolume persistentVolumeClaimWorkspaceVolume(claimName: JENKINS_WORKSPACE)
                }
            }

            stages {
                stage("CHECK & UPDATE VERSIONS") {
                    steps {
                        script {
                            if (NOT_USE_WORKSPACE == true) {
                                check_out()
                            }
                            DESTINATION_ENV = getDestinationEnvironment()
                            BRANCH_NAME_FLATTENED = getFlattenedBranchName()
                            PARENT_VERSION = getCorrectedPomVersion()
                        }
                        sh "echo '================================================================='"
                        sh "echo '                      ${DESTINATION_ENV} mode'"
                        sh "echo '================================================================='"
                        sh "echo 'Current Branch name   : ${env.BRANCH_NAME}'"
                        sh "echo 'Program Version       : ${PARENT_VERSION}'"
                        sh "echo 'Flattened Branch name : ${BRANCH_NAME_FLATTENED}'"
                        script {
                            withCredentials([file(credentialsId: 'sa-ccc-xxx-maven-settingxml', variable: 'settingFile')]) {
                                sh "echo '>>> Applying parent version in POM'"
                                sh "mvn -gs ${settingFile} org.codehaus.mojo:versions-maven-plugin:2.9.0:set -DnewVersion=${PARENT_VERSION}"
                            }
                            sh "echo 'Updated Program Version : ${PARENT_VERSION}'"
                        }
                    }
                }

                stage("MVN BUILD") {
                    steps {
                        withCredentials([file(credentialsId: 'sa-ccc-xxx-maven-settingxml', variable: 'settingFile')]) {
                            script {
                                if (NOT_USE_WORKSPACE) {
                                    unstash name: "webapp-target"
                                } else {
                                    sh "mkdir -p webapp/target; cp -r webapp/dist/pan-ui webapp/target/pan-webapp"
                                }

                                // temporarily wire the production onto the val marklogic instance so that there's no 
                                // service interruption for users while we reinject all the data in the prod mkl instance
                                SWAP_PROD_AND_VAL = false

                                if (SWAP_PROD_AND_VAL) {
                                    if (env.BRANCH_NAME == "develop") {
                                        // val front => prod db
                                        sh "mvn -gs ${settingFile} -f backend/pom.xml clean package -Pprod"
                                    } else if (env.BRANCH_NAME == "master") {
                                        // prod front => val db 
                                        sh "mvn -gs ${settingFile} -f backend/pom.xml clean package -Pval"
                                    }
                                } else {
                                    // usual mode where val front => val db and prod front => prod db
                                    sh "mvn -gs ${settingFile} -f backend/pom.xml clean package -P${DESTINATION_ENV}"
                                }

                                // stack backend
                                if (NOT_USE_WORKSPACE == true) {
                                    stash includes: "backend/target/", name: "backend-target"
                                }
                            }
                        }
                    }
                }

                stage("PUSH TO ARTIFACTORY") {
                    steps {
                        withCredentials([file(credentialsId: 'sa-ccc-xxx-maven-settingxml', variable: 'settingFile')]) {
                            sh "echo 'PUSH TO ARTIFACTORY'"
                            sh "mvn -gs ${settingFile} -Dmaven.test.skip=true deploy -P${DESTINATION_ENV} -f backend/pom.xml"
                        }
                    }
                }
            }
        }

        stage('SONAR CHECK') {
            agent {
                kubernetes {
                    cloud "basic-cluster-prod"
                    inheritFrom '2.6_sonar-5.0.1.3006_jdk-21.0.5'
                    yamlMergeStrategy merge()
                    //workspaceVolume persistentVolumeClaimWorkspaceVolume(claimName: JENKINS_WORKSPACE)
                }
            }

            stages {

                stage("SONAR CODE QUALIFICATION") {
                    steps {

                        script {
                            if (NOT_USE_WORKSPACE) {
                                check_out()
                                unstash name: "webapp-target"
                                unstash name: "backend-target"
                            }
                            DESTINATION_ENV = getDestinationEnvironment()
                            BRANCH_NAME_FLATTENED = getFlattenedBranchName()
                            if (NOT_USE_WORKSPACE == true) {
                                PARENT_VERSION = getCorrectedPomVersion()
                            } else {
                                PARENT_VERSION = getPomVersion()
                            }
                        }
                        sh "echo '================================================================='"
                        sh "echo '                      ${DESTINATION_ENV} mode'"
                        sh "echo '================================================================='"
                        sh "echo 'Current Branch name   : ${env.BRANCH_NAME}'"
                        sh "echo 'Program Version       : ${PARENT_VERSION}'"
                        sh "echo 'Flattened Branch name : ${BRANCH_NAME_FLATTENED}'"
                        // sonar analysis
                        withCredentials([file(credentialsId: 'sa-ccc-xxx-maven-settingxml', variable: 'settingFile')]) {
                            sh "sonar-scanner \
                                -Dsonar.projectName=pan/0000-pan-BackEnd \
                                -Dsonar.projectKey=basicaccc:pan:51ddf6e0-e-er-b7c4-sdd \
                                -Dsonar.login=xxx \
                                -Dsonar.projectName=pan/0000-pan-BackEnd \
                                -Dsonar.host.url=https://sonarqube.moctar.corp \
                                -Dsonar.java.binaries=\"backend/target/classes\" \
                                -Dsonar.branch.name=${env.BRANCH_NAME}\
                            "
                        }
                    }
                }
            }
        }

        stage('BUILD DOCKER IMAGE') {
            agent {
                kubernetes {
                    cloud "basic-cluster-prod"
                    inheritFrom '2.4_dind-20.10.14'
                    yamlMergeStrategy merge()
                    //workspaceVolume persistentVolumeClaimWorkspaceVolume(claimName: JENKINS_WORKSPACE)
                }
            }

            stages {

                stage('BUILD IMAGE') {
                    steps {
                        script {
                            if (NOT_USE_WORKSPACE) {
                                check_out()
                                unstash name: "backend-target"
                            }
                            DESTINATION_ENV = getDestinationEnvironment()
                            BRANCH_NAME_FLATTENED = getFlattenedBranchName()
                            if (NOT_USE_WORKSPACE == true) {
                                PARENT_VERSION = getCorrectedPomVersion()
                            } else {
                                PARENT_VERSION = getPomVersion()
                            }
                        }
                        sh "echo '================================================================='"
                        sh "echo '                      ${DESTINATION_ENV} mode'"
                        sh "echo '================================================================='"
                        sh "echo 'Current Branch name   : ${env.BRANCH_NAME}'"
                        sh "echo 'Program Version       : ${PARENT_VERSION}'"
                        sh "echo 'Flattened Branch name : ${BRANCH_NAME_FLATTENED}'"

                        sh "ls backend"
                        sh "ls backend/target/"
                        sh "echo 'COPY WAR'"
                        sh "mkdir -p dist"
                        sh "cp -p backend/target/pan-backend-${PARENT_VERSION}.war dist/pan-ui.war"
                        sh "echo 'BUILD IMAGE'"
                        sh "docker build --rm -t pan-ui:v${PARENT_VERSION} --pull ."
                    }
                }

                stage('PUSH IMAGE') {
                    steps {
                        sh "echo 'PUSH IMAGE'"
                        sh "docker tag pan-ui:v${PARENT_VERSION} artifactory.fr.eu.moctar.corp:30019/hosting/pan-ui:v${PARENT_VERSION}"
                        sh "docker image push artifactory.fr.eu.moctar.corp:30019/hosting/pan-ui:v${PARENT_VERSION}"
                    }
                }
            }
        }

        stage('DEPLOY IMAGE (SLAVE DinD)') {
            agent {
                kubernetes {
                    cloud "basic-cluster-prod"
                    inheritFrom '2.4_helm-3.8.2'
                    yamlMergeStrategy merge()
                    //workspaceVolume persistentVolumeClaimWorkspaceVolume(claimName: JENKINS_WORKSPACE)
                }
            }

            stages {
                stage("CHECK-OUT") {
                    when {
                        expression {
                            NOT_USE_WORKSPACE == true
                        }
                    }
                    steps {
                        script {
                            check_out()
                            DESTINATION_ENV = getDestinationEnvironment()
                            BRANCH_NAME_FLATTENED = getFlattenedBranchName()
                        }
                    }
                }

                stage('CHECK NAMESPACES K8S') {
                    steps {
                        script {
                            if (env.BRANCH_NAME == "master") {
                                env.KUBE_CONFIG_CREDENTIALS_ID = "kubeconfig-reta-apps-prod-sa-ccc-xxx"
                            } else if (env.BRANCH_NAME == "develop") {
                                env.KUBE_CONFIG_CREDENTIALS_ID = "kubeconfig-reta-apps-prod-sa-ccc-xxx"
                            } else if (env.BRANCH_NAME == "retaval") {
                                env.KUBE_CONFIG_CREDENTIALS_ID = "kubeconfig-reta-apps-val-sa-ccc-xxx"
                            } else {
                                // dev 
                                env.KUBE_CONFIG_CREDENTIALS_ID = "kubeconfig-reta-apps-val-sa-ccc-xxx"
                            }
                        }

                        script {

                            withCredentials([file(credentialsId: env.KUBE_CONFIG_CREDENTIALS_ID, variable: 'kubeConfigFile')]) {
                                if (env.BRANCH_NAME == "master") {
                                    NS = NSPROD
                                } else if (env.BRANCH_NAME == "develop") {
                                    NS = NSVAL
                                } else if (env.BRANCH_NAME == "retaval") {
                                    NS = NSretaVAL
                                } else {
                                    NS = NSDEV
                                }
                            }
                        }
                    }
                }

                stage("CLEANUP DEV NAMESPACE") {
                    when {
                        not {
                            anyOf {
                                branch "master"
                            }
                        }
                    }
                    steps {
                        // kubeconfig-reta-apps-val-sa-ccc-xxx
                        // kubeconfig-reta-apps-prod-sa-ccc-xxx
                        withCredentials([file(credentialsId: env.KUBE_CONFIG_CREDENTIALS_ID, variable: 'kubeConfigFile')]) {
                            // Get GIT Branches
                            script {
                                giturl = sh(returnStdout: true, script: 'git config remote.origin.url').trim().replaceFirst("^(http[s]?://)", "")
                            }
                            withCredentials([usernamePassword(credentialsId: 'sa-ccc-xxx-gheprivate-token', passwordVariable: 'gitToken', usernameVariable: 'gitUser')]) {
                                sh "git ls-remote --heads https://${gitUser}:${gitToken}@${giturl} | grep \"refs/\" | cut -d '/' -f3- | tr '/' '-' | tr '_' '-' | sort -u > remote-branches.txt"
                            }
                            // Get kube deployments
                            catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                                sh "kubectl --kubeconfig ${kubeConfigFile} --namespace=${NSDEV} get deployments,statefulsets,pods,services --show-labels | grep -Eo \"branch=[-0-9a-zA-Z]+\" | sed -e 's/branch=//g' | sort -u > deployed-branches.txt"
                                sh "comm -23 deployed-branches.txt remote-branches.txt > deployments-to-remove.txt"
                                // clean reta deployment : Attention il faut respecter les conventions de nommage defini dans ./deploy/* : Tous le composant s'appelle : pan-${branch}
                                sh "echo 'DEPLOYMENT TO REMOVE :' ; cat deployments-to-remove.txt "
                                sh "cat deployments-to-remove.txt | while read branch; do echo \"==> Removing resources with selector \$branch\"; kubectl --kubeconfig ${kubeConfigFile} --namespace=${NSDEV} delete deployments,pods,statefulsets,services,vs pan-\${branch} || true;  done "
                            }
                        }
                    }
                }

                stage('SETUP DEPLOYMENT FILES') {
                    steps {
                        script {
                            // Update deployment.yaml and virtual-service.yaml
                            if (env.BRANCH_NAME == "master") {
                                panoraPREFIX = "panora/search"
                                REPLICAS = "3"
                                sh "cat deploy/network-security-policy.yaml | sed -e 's/#SUFFIX#/prod/g' > network-security-policy.yaml"
                                sh "cat deploy/deployment.yaml | sed -e 's/#VERSION#/${PARENT_VERSION}/g' | sed -e 's/#SUFFIX#/prod/g' | sed -e 's/#BRANCH#/${env.BRANCH_NAME}/g' | sed -e 's/#ENV#/${NS}/g' | sed -e 's/#REPLICAS#/${REPLICAS}/g' > deployment.yaml"
                                sh "cat deploy/virtual-service.yaml | sed -e 's+#GATEWAY#+gateway+g' | sed -e 's+#GATEWAYHOST#+gateway.reta.eu.moctar.corp+g' | sed -e 's+#SUFFIX#+prod+g' | sed -e 's+#panoraPREFIX#+${panoraPREFIX}+g' > virtual-service.yaml"
                            } else if (env.BRANCH_NAME == "develop") {
                                panoraPREFIX = "panora-val/search"
                                REPLICAS = "2"
                                sh "cat deploy/deployment.yaml | sed 's/IfNotPresent/Always/g' > deployment.yaml"
                                sh "cat deploy/network-security-policy.yaml | sed -e 's/#SUFFIX#/val/g' > network-security-policy.yaml"
                                sh "cat deploy/deployment.yaml | sed -e 's/#VERSION#/${PARENT_VERSION}/g' | sed -e 's/#SUFFIX#/val/g' | sed -e 's/#BRANCH#/${env.BRANCH_NAME}/g' | sed -e 's/#ENV#/${NS}/g' | sed -e 's/#REPLICAS#/${REPLICAS}/g' > deployment.yaml"
                                sh "cat deploy/virtual-service.yaml | sed -e 's+#GATEWAY#+gateway+g' | sed -e 's+#GATEWAYHOST#+gateway.reta.eu.moctar.corp+g' | sed -e 's+#SUFFIX#+val+g' | sed -e 's+#panoraPREFIX#+${panoraPREFIX}+g' > virtual-service.yaml"
                            } else if (env.BRANCH_NAME == "retaval") {
                                panoraPREFIX = "panora/search"
                                REPLICAS = "1"
                                sh "cat deploy/network-security-policy.yaml | sed -e 's/#SUFFIX#/retaval/g' > network-security-policy.yaml"
                                sh "cat deploy/deployment.yaml | sed -e 's/#VERSION#/${PARENT_VERSION}/g' | sed -e 's/#SUFFIX#/retaval/g' | sed -e 's/#BRANCH#/${env.BRANCH_NAME}/g' | sed -e 's/#ENV#/${NS}/g' | sed -e 's/#REPLICAS#/${REPLICAS}/g' > deployment.yaml"
                                sh "cat deploy/virtual-service.yaml | sed -e 's+#GATEWAY#+gateway-val+g' | sed -e 's+#GATEWAYHOST#+gateway2-val.reta-val.eu.moctar.corp+g' | sed -e 's+#SUFFIX#+retaval+g' | sed -e 's+#panoraPREFIX#+${panoraPREFIX}+g' | sed -e 's+gateway.reta.eu.moctar.corp+gateway2-val.reta-val.eu.moctar.corp+g'  > virtual-service.yaml"
                            } else {
                                panoraPREFIX = "panora-dev/search-dev-${BRANCH_NAME_FLATTENED}"
                                REPLICAS = "1"
                                sh "cat deploy/network-security-policy.yaml | sed -e 's/#SUFFIX#/dev/g' > network-security-policy.yaml"
                                sh "cat deploy/deployment.yaml | sed -e 's/#VERSION#/${PARENT_VERSION}/g' | sed -e 's/#SUFFIX#/${BRANCH_NAME_FLATTENED}/g' | sed -e 's/#BRANCH#/${BRANCH_NAME_FLATTENED}/g' | sed -e 's/#ENV#/${NS}/g' | sed -e 's/#REPLICAS#/${REPLICAS}/g' > deployment.yaml"
                                sh "cat deploy/virtual-service.yaml | sed -e 's+#GATEWAY#+gateway-val+g' | sed -e 's+#GATEWAYHOST#+gateway2-val.reta-val.eu.moctar.corp+g' | sed -e 's+#SUFFIX#+${BRANCH_NAME_FLATTENED}+g' | sed -e 's+#panoraPREFIX#+${panoraPREFIX}+g' > virtual-service.yaml"
                            }
                        }
                    }
                }

                stage('DEPLOYMENT K8S') {
                    steps {
                        withCredentials([file(credentialsId: env.KUBE_CONFIG_CREDENTIALS_ID, variable: 'kubeConfigFile')]) {
                            sh "kubectl --kubeconfig ${kubeConfigFile} apply --namespace ${NS} -f deployment.yaml -f virtual-service.yaml -f deploy/config-map.yaml -f network-security-policy.yaml"
                            script {
                                if (env.BRANCH_NAME == "master") {
                                    sh "kubectl --kubeconfig ${kubeConfigFile} rollout restart deployment/pan-prod -n ${NS}"
                                    sh "kubectl --kubeconfig ${kubeConfigFile} rollout status deployment/pan-prod -n ${NS}"
                                } else if (env.BRANCH_NAME == "develop") {
                                    sh "kubectl --kubeconfig ${kubeConfigFile} rollout restart deployment/pan-val -n ${NS}"
                                    sh "kubectl --kubeconfig ${kubeConfigFile} rollout status deployment/pan-val -n ${NS}"
                                } else if (env.BRANCH_NAME == "retaval") {
                                    sh "kubectl --kubeconfig ${kubeConfigFile} rollout restart deployment/pan-retaval -n ${NS}"
                                    sh "kubectl --kubeconfig ${kubeConfigFile} rollout status deployment/pan-retaval -n ${NS}"
                                } else {
                                    sh "kubectl --kubeconfig ${kubeConfigFile} rollout restart deployment/pan-${BRANCH_NAME_FLATTENED} -n ${NS}"
                                    sh "kubectl --kubeconfig ${kubeConfigFile} rollout status deployment/pan-${BRANCH_NAME_FLATTENED} -n ${NS}"
                                }
                            }
                        }
                        script {
                            INGRESS_URL = sh(returnStdout: true, script: "cat ingress.yaml | grep host | cut -d: -f2 | sed -e \"s/'//g\" | sed -e 's/ //g'| tr -d '\n'")
                            GATEWAY = sh(returnStdout: true, script: "cat virtual-service.yaml | grep hosts -A1 | tr -d '[:space:]' | sed 's/hosts:-//' | sed 's/\"//g' ")
                            WEBAPP_URI = sh(returnStdout: true, script: "cat virtual-service.yaml | grep prefix | grep '/ui' | tr -d '[:space:]' | sed 's/prefix://'")
                            API_URI = sh(returnStdout: true, script: "cat virtual-service.yaml | grep prefix | grep '/ui' | tr -d '[:space:]' | sed 's/prefix://' | sed 's,/ui,/api,g' ")
                            SWAGGER_URI = sh(returnStdout: true, script: "cat virtual-service.yaml | grep prefix | grep '/api-docs' | tr -d '[:space:]' | sed 's/prefix://'")
                            JASMINE_URI = sh(returnStdout: true, script: "cat virtual-service.yaml | grep prefix | grep '/jasmine' | tr -d '[:space:]' | sed 's/prefix://'")
                            currentBuild.rawBuild.project.description = '<p>Last successful builds : \
                                                                            <ul>\
                                                                                <li> Version : ' + PARENT_VERSION + '</li>\
                                                                            </ul>\
                                                                         </p>\
                                                                         <p>URLs : \
                                                                            <ul>\
                                                                                <li>GET Ontology ttl                       : https://' + GATEWAY + API_URI + 'v1/lexicon/ontology' + '</li>\
                                                                                <li>pan webapp (with SSO)            : https://' + GATEWAY + WEBAPP_URI + '</li>\
                                                                                <li>pan shareable Swagger (with SSO) : https://' + GATEWAY + SWAGGER_URI + '/</li>\
                                                                                <li>pan full Swagger (with SSO)      : https://' + GATEWAY + SWAGGER_URI + '/?mode=full' + '</li>\
                                                                                <li>Jasmine Unit tests - moctar Commercial (On VAL only)  : https://pan.2k77-reta.aws.cloud.moctar-v.corp/ui/jasmine/?runas=dd&debug=false&mode=parallel /</li>\
                                                                                <li>Jasmine Unit tests - moctar ff (On VAL only)  : https://pan.2k77-reta.aws.cloud.moctar-v.corp/ui/jasmine/?runas=ff&debug=false&mode=parallel /</li>\
                                                                                <li>Jasmine Unit tests - moctar Canada     (On VAL only)  : https://pan.2k77-reta.aws.cloud.moctar-v.corp/ui/jasmine/?runas=moctar_canada&debug=false&mode=parallel /</li>\
                                                                            </ul>\
                                                                         </p>'
                        }
                    }
                }
            }

            post {
                always {
                    script {
                        if (NOT_USE_WORKSPACE == false && CLEAN_WORKSPACE == true) {
                            cleanWs(cleanWhenNotBuilt: true,
                                deleteDirs: true,
                                disableDeferredWipeout: true,
                                notFailBuild: true)
                        }
                    }
                }
            }

        }
    }
}