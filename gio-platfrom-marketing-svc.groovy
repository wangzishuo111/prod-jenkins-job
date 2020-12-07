pipeline {
    agent {
        kubernetes {
            defaultContainer 'capistrano'
            yaml """
apiVersion: v1
kind: Pod
metadata:
  namespace: jenkins
spec:
  securityContext:
    fsGroup: 1000
  containers:
  - name: jnlp
    image: uhub.service.ucloud.cn/growing/inbound-agent:4.3-9
    volumeMounts:
    - mountPath: /home/jenkins/agent/workspace
      name: workspace
  - name: node
    image: uhub.service.ucloud.cn/growing/node:14-buster
    resources:
      requests:
        memory: "4096Mi"
        cpu: "2000m"
      limits:
        memory: "6142Mi"
        cpu: "4000m"
    volumeMounts:
    - mountPath: /usr/local/share/.cache
      name: yarn-cache
    - mountPath: /home/jenkins/agent/workspace
      name: workspace
    tty: true
    command:
    - cat
  - name: capistrano
    image: uhub.service.ucloud.cn/growing/capistrano:3-alpine
    volumeMounts:
    - mountPath: /home/jenkins/agent/workspace
      name: workspace
    tty: true
    command:
    - cat
  - name: cdn
    image: uhub.service.ucloud.cn/growing/cdn-purge:1.0-alpine
    env:
    - name: AKAMAI_EDGERC_SECTION
      value: 'default'
    - name: HTTP_PROXY
      value: "http://10.5.1.6:8118"
    - name: HTTPS_PROXY
      value: "http://10.5.1.6:8118"
    volumeMounts:
    - mountPath: /home/jenkins/agent/workspace
      name: workspace
    tty: true
    command:
    - cat
  imagePullSecrets:
  - name: regcred
  volumes:
  - name: yarn-cache
    persistentVolumeClaim:
      claimName: prod-jenkins-yarn-cache
  - name: workspace
    persistentVolumeClaim:
      claimName: prod-jenkins-workspace
"""
        }
    }
    options {
        ansiColor('xterm')
        timeout(time: 60, unit: 'MINUTES')
        buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '20')
    }
    parameters{
        string(defaultValue: 'master', description: '代码分支', name: 'GIT_BRANCH', trim: true)
        choice(choices: ['uproduction0', 'uproduction1'], description: '环境', name: 'BUILD_ENV')
        choice(choices: ['None', 'Deploy', 'Register'], description: '操作', name: 'ACTION')
        booleanParam(defaultValue: false, description: '是否编译代码？', name: 'BUILD_ENABLED')
        string(description: 'Relase Notes', name: 'RELEASE_NOTE', trim: true)
    }
    environment {
        CODE_REPO = "ssh://vcs-user@codes.growingio.com/diffusion/332/gio-platform-marketing.git"
        S3_BUCKET = 'assets.growingio.com'
        CDN_DOMAIN = 'assets.giocdn.com'
        PREFIX = 'app-marketing'
    }
    stages {
        stage('Be sure publish date'){
            steps {
                script {
                    def now = new Date()
                    DATE = now.format("yyyyMMdd", TimeZone.getTimeZone('UTC'))
                }
            }
        }
        stage('Prepare code') {
            when {
                allOf {
                    equals expected: true, actual: params.BUILD_ENABLED 
                }  
            }
            steps {
                cleanWs()
                git branch: params.GIT_BRANCH, url: CODE_REPO, credentialsId: 'jenkins-phabricator-ssh-key'
            }
        }
        stage('Build') {
            when {
                allOf {
                    equals expected: true, actual: params.BUILD_ENABLED 
                }  
            }
            steps {
                container('node') {
                    sh 'sed -i \'s/max_old_space_size=8192/max_old_space_size=4096/g\' package.json'
                    sh 'yarn config set registry https://nexus.growingio.com/repository/npm-group/ --global'
                    sh 'yarn config set network-timeout 600000 --global'
                    sh 'yarn install --frozen-lockfile'
                    sh "yarn run build -- --env.uglify --env.production --env.uglifyCache ${HOME}/.cache/uglify --env.publicPath '//${CDN_DOMAIN}/${PREFIX}/${DATE}'"
                }
            }
        }
        stage('Deploy'){
            when {
                equals expected: 'Deploy', actual: params.ACTION 
            }
            steps {
                container('capistrano') {
                    withAWS(region: 'cn-north-1', credentials: 'aws-s3-access') {
                        s3Upload acl: 'PublicRead', bucket: S3_BUCKET, path: "${PREFIX}/${DATE}", workingDir: 'result', includePathPattern: '**/*.js', contentType: 'application/javascript; charset=utf-8'
                        s3Upload acl: 'PublicRead', bucket: S3_BUCKET, path: "${PREFIX}/${DATE}", workingDir: 'result', includePathPattern: '**/*', excludePathPattern: '**/*.js', contentType: ''
                    }
                }
            }
        }
        stage('Fresh CDN'){
            when {
                equals expected: 'Deploy', actual: params.ACTION 
            }
            steps {
                container('cdn') {
                    withCredentials([
                        string(credentialsId: 'cdn-wangsu-username', variable: 'WANGSU_USERNAME'), 
                        string(credentialsId: 'cdn-wangsu-api-key', variable: 'WANGSU_API_KEY'), 
                        file(credentialsId: 'akamai-secret-file', variable: 'AKAMAI_EDGERC')])
                    {
                        sh "cdn-purge --url 'https://${CDN_DOMAIN}/${PREFIX}/${DATE}/index.html'"
                        sh "cdn-purge --url 'https://${CDN_DOMAIN}/${PREFIX}/${DATE}/entry.html'"
                    }
                }
            }
        }
        stage('Register'){
            when {
                equals expected: 'Register', actual: params.ACTION 
            }
            steps {
                script {
                    ENV_ALIAS = [
                        "uproduction0": "prod0",
                        "uproduction1": "prod1"
                    ]
                    ENV = ENV_ALIAS.(params.BUILD_ENV)
                }
                container('cdn') {
                    echo "${ENV}"
                    sh "curl --request PUT --data 'https://${CDN_DOMAIN}/${PREFIX}/${DATE}' 'http://consul.growingio.cn/v1/kv/gio-platform-marketing/${DEPLOY_ENV}'"
                }
            }
        }
    }
    post {
        always {
            echo 'send email'
        }
    }
}
