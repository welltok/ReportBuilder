#!groovy

@Library('wt_activate') _			// Load Shared Functions

def agent_name;

pipeline {
	agent {
		label "agent-ubuntu-16"			// Create a Linux server from AMI "agent-ubuntu-16" to run on
	}
	options {
		checkoutToSubdirectory( BUILD_ID ) 	// Unique workspace folder. If many jobs run on the same server, they will each have their own workspace
		timestamps()				// Add timestamps to the output
		timeout(time: 20, unit: 'MINUTES')
	}
	parameters {
		string name: 'BRANCH_NAME', defaultValue: '', description: 'Specify the branch (refs/heads/master) or tag (refs/tags/US12345) to build from', trim: true
		booleanParam name: 'Deploy_to_GemFury', defaultValue: false, description: 'Should a successful build from branch <b>master</b> be pushed to <b>GemFury</b>?'
		booleanParam name: 'Debug_Agent', defaultValue: false, description: 'Do you want to run the build on a <b>debug</b> type agent?'
	}
	environment {
                // Standard variable. Always define "ArtifactDir"
		ArtifactDir = "$WORKSPACE/$BUILD_ID/Artifacts"
		Build_Kit = "gem"
		//
		// Configure values used by your build script
		//
		SOURCE="Jenkins"
		MAIN_AGENT="agent-ubuntu-16"
		DEBUG_AGENT="ubuntu-16-debug"
		//
		// Load secrets from the Build Credetial Store by its ID
		//
		BUNDLE_GEM__FURY__IO = credentials('0ffb0311-334e-479a-8fb5-8a3737aec5a3')
	}
	stages {
		stage("Choose agent") {
			steps {
				script {
					if (params.Debug_Agent == true) {
						agent_name = DEBUG_AGENT
					} else {
						agent_name = MAIN_AGENT
					}
					echo "[DEBUG] Using agent $agent_name"
				}
			}
		}
		stage("Wrapper") {
			agent { label agent_name }
			stages {
				stage("Initialize") {
					steps {
						ws( "${env.WORKSPACE}/$BUILD_ID" ) {
							script {
								//
								// Update Github status to "Pending"; Set standard variables
								//
								shared.InitializeBuild();
							}	//  script
						}	//  workspace
					}	//  steps
				}	//  stage InitializeBuild
				stage("Build") {
					steps {
						ws( "${env.WORKSPACE}/$BUILD_ID" ) {
							script {
						//
						// Define flags as "0" or "1" to determine which artifact to build, based on the workflow
						// Return these in a "map" structure, available as
						//   flags[ "Deploy_to_GemFury" ]   -or- flags.Deploy_to_GemFury
						// Return these in "map" structure
						//
						def flags = shared.preBuild();
						if ( "${debug}" == "yes" ) {
							echo "preBuild flags:"
							flags.each{ k, v -> println "      ${k}:${v}" }
						}
						commit_tag = flags.commit_tag

						//
						//  "commit_tag" is a groovy variable, but is not visible inside the shell script
						//  "env.tag" is an environment variable visible within the shell script, but changes to "env.tag" to not propagate back out to "commit_tag"
						//
						//  Note: this block is in the Groovy script, and uses "//" to indicate a comment
						//
						env.commit_tag = commit_tag
						env.WORKSPACE = WORKSPACE
						env.debug = debug

						rtnStatus = sh returnStatus: true, script: '''#!/bin/bash -ex
							#
							# All code before this is Jenkins setting up the build
							# The build script for "this_component" begins here
							#
							# Note: this block is in a "bash" shell script, and uses "#" to indicate a comment
							#

							cd $WORKSPACE
							echo "[DEBUG] Current directory, contents, env.variables"
							pwd
							ls -al
							env | set

							bundle install --path vendor/bundle

							#
							# All code after this is Jenkins finishing up the build
							# The build script for this component ends here
							#
						'''

						echo "[DEBUG] Deploy_to_GemFury: ${env.Deploy_to_GemFury}"
						echo "[DEBUG] rtnStatus: ${rtnStatus}"
						if ( rtnStatus != 0 ) {
							env.Deploy_to_GemFury = false
							echo "[DEBUG] set Deploy_to_GemFury to: ${env.Deploy_to_GemFury}"
						}

						//
						//  Set Github status, update build description, potentially start other jobs
						//
								shared.postBuild( rtnStatus, commit_tag, "Built from branch: <strong>${env.BRANCH_NAME}:${commit_tag}</strong>" );
							}	//  script
						}	//  Workspace
					}	//  steps
				}	//  stage Build
				stage("Deploy to GemFury") {
					when {
						equals expected : "true", actual : "${env.Deploy_to_GemFury}"
					}
					steps {
						ws( "${env.WORKSPACE}" ) {
							script {
								shared.InitializeDeploy()
								env.deploy_target = "GemFury"
								env.debug = debug
								env.BUNDLE_GEM__FURY__IO = BUNDLE_GEM__FURY__IO
								rtnStatus = sh returnStatus: true, script: '''#!/bin/bash -ex
									echo "[DEBUG] Current directory, contents, env.variables"
									pwd
									ls -al
									env | set

									gem build report_builder.gemspec | tee gem.stdout
									rtnStatus=$?
									if [ $rtnStatus == 0 ]; then
										export filename=`awk '/File:/ { gsub(/File:/, "", $0); gsub(/ /, "", $0); print $0 }' gem.stdout `
										curl -F package=@$filename https://$BUNDLE_GEM__FURY__IO@push.fury.io/welltok
									fi
								'''

								shared.postDeploy( rtnStatus, env.deploy_target, "Built from branch: <strong>${env.BRANCH_NAME}:${commit_tag}</strong>" );
							} 	// script
						} 	// Workspace
					}	// Steps
				}	//  stage Deploy
			} // stages inner
		} // stage wrapper
	}	//  stages outer
	post {
		success {
			script {
				echo "              Post Build: 'SUCCESS'"
				shared.setBuildStatus("Build succeeded", "SUCCESS");
			}
		}
		failure {
			script {
				echo "              Post Build: 'FAILURE'"
				shared.setBuildStatus("Build failed", "FAILURE");
			}
		}
		always {
			junit allowEmptyResults: true, testResults: "**/$BUILD_ID/junit_test_result.xml"
		}
	}
}	//  pipeline
