#
# Copyright 2017 XEBIALABS
#
# Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
#

from com.xebialabs.deployit.exception import NotFoundException
from com.xebialabs.xlrelease.api.v1.forms import Comment, Variable
from com.xebialabs.xlrelease.domain import Task

RELEASE_ID_PARAM = 'releaseId'
ON_FAILURE_USER_PARAM = 'onFailureUser'
MANUAL_TASK_TYPE = 'xlrelease.Task'
BOOLEAN_VARIABLE_TYPE = 'xlrelease.BooleanVariable'
RELEASE_FAILED_VARIABLE_NAME = 'releaseFailed'
ON_FAILURE_PHASE_NAME = 'onFailure'
IS_ON_FAILURE_PHASE = lambda phase: phase.getTitle() == ON_FAILURE_PHASE_NAME
IS_PARALLEL_GROUP = lambda task: task.getType() == 'xlrelease.ParallelGroup'

def has_onFailure_phase(release):
    for phase in release.getPhases():
        if IS_ON_FAILURE_PHASE(phase):
            return True
    return False

def has_failed_phase(release):
    for phase in release.getPhases():
        if phase.isFailed():
            return True
    return False

def add_placeholder_task(release, assignedUser):
    for phase in release.getPhases():
        if phase.isFailed():
            tasks = phase.getTasks()
            numTasks = len(tasks)
            for i in range(numTasks):
                if tasks[i].isFailed():
                    logger.trace('Adding manual task in phase {} at position {}', phase.getTitle(), i+1)
                    placeholderTask = Task.fromType(MANUAL_TASK_TYPE)
                    placeholderTask.setTitle('Skip to Fallback')
                    placeholderTask.setDescription('Automatically added by onFailure handler')
                    placeholderTask.setOwner(assignedUser)
                    savedPlaceholderTask = phaseApi.addTask(phase.getId(), placeholderTask, i+1)

    return savedPlaceholderTask.getId()

def skip_task(taskId, assignedUser, comment):
    taskApi.assignTask(taskId, assignedUser)
    taskApi.skipTask(taskId, comment)

def new_boolean_var(name, value, required=False, label=None, description=None):
    # setting a dummy value since we can only pass a string here
    variable = Variable(name, None, required)
    variable.setType(BOOLEAN_VARIABLE_TYPE)
    variable.setValue(value)
    if label:
        variable.setLabel(label)
    if description:
        variable.setDescription(description)
    return variable

if not RELEASE_ID_PARAM in request.query:
    responseMsg = "Required parameter '%s' missing. Doing nothing" % (RELEASE_ID_PARAM)
elif not ON_FAILURE_USER_PARAM in request.query:
    responseMsg = "Required parameter '%s' missing. Doing nothing" % (ON_FAILURE_USER_PARAM)
else:
    releaseId = request.query[RELEASE_ID_PARAM]
    if not releaseId.startswith('Applications/'):
        releaseId = "Applications/%s" % (releaseId)

    onFailureUsername = request.query[ON_FAILURE_USER_PARAM]

    logger.info('Invoking onFailure handler for releaseId {} with tracking user {}', releaseId, onFailureUsername)
    try:
        release = releaseApi.getRelease(releaseId)
    except NotFoundException:
        release = None

    if not release:
        logger.debug("No release '{}' found. Doing nothing", releaseId)
        responseMsg = "Release '%s' not found. Doing nothing" % (releaseId)
    elif not has_onFailure_phase(release):
        logger.debug('No phase {} found. Doing nothing', ON_FAILURE_PHASE_NAME)
        responseMsg = "onFailure handler does not apply to this release as there is no phase named '%s'. Doing nothing" % (ON_FAILURE_PHASE_NAME)
    elif not has_failed_phase(release):
        logger.debug('No failed phase found. Doing nothing')
        responseMsg = 'onFailure handler does not apply to this release as there is no failed phase. Doing nothing'
    elif RELEASE_FAILED_VARIABLE_NAME in release.getVariablesByKeys():
        logger.debug('Variable "{}" already present. Doing nothing', RELEASE_FAILED_VARIABLE_NAME)
        responseMsg = 'onFailure handler already invoked for this release. Doing nothing'
    else:
        logger.debug('Adding "{}" variable', RELEASE_FAILED_VARIABLE_NAME)
        releaseFailedVar = new_boolean_var(RELEASE_FAILED_VARIABLE_NAME, True, False, 'Has this release failed?', 'Automatically set by onFailure handler')
        releaseApi.createVariable(release.getId(), releaseFailedVar)
        
        # add a manual placeholder task immediately after the first failure
        # so we can activate the release again
        logger.debug('Adding placeholder task')
        placeholderTaskId = add_placeholder_task(release, onFailureUsername)

        logger.debug('Skipping failed tasks')
        skipComment = Comment('Skipped by onFailure handler')
        for task in release.getAllTasks():
            if task.isFailed() and not IS_PARALLEL_GROUP(task):
                logger.trace('Skipping failed task {}', task.getId())
                taskApi.skipTask(task.getId(), skipComment)

        logger.debug('Skipping planned tasks')
        for phase in release.getPhases():
            if not IS_ON_FAILURE_PHASE(phase):
                for task in phase.getAllTasks():
                    # leave the placeholder task running
                    if (task.isPlanned() or task.isInProgress()) and not IS_PARALLEL_GROUP(task) and task.getId() != placeholderTaskId:
                        logger.trace('Skipping planned or in progress task {}', task.getId())
                        skip_task(task.getId(), onFailureUsername, skipComment)
        
        logger.debug('Skipping placeholder task')
        skip_task(placeholderTaskId, onFailureUsername, skipComment)

        responseMsg = "Successfully executed onFailure handler for release '%s'" % (releaseId)'

response.entity = { 'message': responseMsg }
