#!/usr/bin/python
#
# Copyright (C) 2015 John Casey (jdcasey@commonjava.org)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#         http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import fnmatch
import os
import shlex
import shutil
import signal
import subprocess
import sys
from urllib2 import urlopen

def handle_shutdown(signum, frame):
  print "SIGTERM: Stopping Indy."
  process.send_signal(signal.SIGTERM)

def handle_output(process):
  try:
    for c in iter(lambda: process.stdout.read(1), ''):
      sys.stdout.write(c)
  except KeyboardInterrupt:
    print ""
    return

def run(cmd, fail_message='Error running command', fail=True):
  cmd += " 2>&1"
  print cmd
  ret = os.system(cmd)
  if fail is True and ret != 0:
    print "%s (failed with code: %s)" % (fail_message, ret)
    sys.exit(ret)



def runIn(cmd, workdir, fail_message='Error running command', fail=True):
  cmd += " 2>&1"
  olddir = os.getcwd()
  os.chdir(workdir)

  print "In: %s, executing: %s" % (workdir, cmd)

  ret = os.system(cmd)
  if fail is True and ret != 0:
    print "%s (failed with code: %s)" % (fail_message, ret)
    sys.exit(ret)

  os.chdir(olddir)


def copy_over(src, target):
  if not os.path.exists(src):
    return
  
  if os.path.exists(target):
    print "rm -r %s" % target
    shutil.rmtree(target)

  print "cp -r %s %s" % (src, target)
  shutil.copytree(src, target)

def copy_missed(src, target):
  for src_dir, _, files in os.walk(src):
    dst_dir = src_dir.replace(src, target, 1)
    if not os.path.exists(dst_dir):
      os.makedirs(dst_dir)
    for file_ in files:
      src_file = os.path.join(src_dir, file_)
      dst_file = os.path.join(dst_dir, file_)
      if os.path.exists(dst_file):
        continue       
      print "Copy new file: %s" % src_file
      shutil.copyfile(src_file, dst_file) 

def move_and_link(src, target, replaceIfExists=False):
  srcParent = os.path.dirname(src)
  if not os.path.isdir(srcParent):
    print "mkdir -p %s" % srcParent
    os.makedirs(srcParent)

  if not os.path.isdir(target):
    print "mkdir -p %s" % target
    os.makedirs(target)

  if os.path.isdir(src):
    for f in os.listdir(src):
      targetFile = os.path.join(target, f)
      srcFile = os.path.join(src, f)
      print "%s => %s" % (srcFile, targetFile)
      if os.path.exists(targetFile):
        if not replaceIfExists:
          print "Target dir exists: %s. NOT replacing." % targetFile
          continue
        else:
          print "Target dir exists: %s. Replacing." % targetFile

        if os.path.isdir(targetFile):
          print "rm -r %s" % targetFile
          shutil.rmtree(targetFile)
        else:
          print "rm %s" % targetFile
          os.remove(targetFile)

      if os.path.isdir(srcFile):
        print "cp -r %s %s" % (srcFile, targetFile)
        shutil.copytree(srcFile, targetFile)
      else:
        print "cp %s %s" % (srcFile, targetFile)
        shutil.copy(srcFile, targetFile)

    print "rm -r %s" % src
    shutil.rmtree(src)

  print "ln -s %s %s" % (target, src)
  os.symlink(target, src)



# Envar for reading development binary volume mount point
SSH_CONFIG_VOL = '/tmp/ssh-config'

INDY_ETC_DIR_ENVAR = 'INDY_ETC_DIR'
INDY_OPTS_ENVAR = 'INDY_OPTS'

# locations for expanded indy binary
INDY_DIR = '/opt/indy'
BOOT_PROPS = 'boot.properties'
INDY_BIN = os.path.join(INDY_DIR, 'bin')
INDY_ETC = os.path.join(INDY_DIR, 'etc/indy')
INDY_STORAGE = os.path.join(INDY_DIR, 'var/lib/indy/storage')
INDY_DATA = os.path.join(INDY_DIR, 'var/lib/indy/data')
INDY_LOGS = os.path.join(INDY_DIR, 'var/log/indy')
INDY_DATA_PROMOTE = os.path.join(INDY_DATA, 'promote')

# locations on global fs
ETC_INDY = '/etc/indy'
VAR_INDY = '/var/lib/indy'
VAR_STORAGE = os.path.join(VAR_INDY, 'storage')
VAR_DATA = os.path.join(VAR_INDY, 'data')
VAR_LOGS = os.path.join(VAR_INDY, 'logs')
LOGS = '/var/log/indy'

# location supplying /opt/indy/etc/indy
indyEtcDir = os.environ.get(INDY_ETC_DIR_ENVAR)

# location to backup new deployed indy promote scripts
BACKUP_PROMOTE = '/tmp/indy/promote'

# command-line options for indy
opts = os.environ.get(INDY_OPTS_ENVAR) or ''
print "Read indy cli opts: %s" % opts

if os.path.isdir(SSH_CONFIG_VOL) and len(os.listdir(SSH_CONFIG_VOL)) > 0:
  print "Importing SSH configurations from volume: %s" % SSH_CONFIG_VOL
  run("cp -vrf %s /root/.ssh" % SSH_CONFIG_VOL)
  run("chmod -v 700 /root/.ssh", fail=False)
  run("chmod -v 600 /root/.ssh/*", fail=False)

if os.path.isdir(INDY_DIR) is False:
  print "Cannot start, %s does not exist!" % INDY_DIR
  exit(1)

#if indyEtcDir is not None:
#  if os.path.isdir(INDY_ETC):
#    print "Clearing pre-existing Indy etc directory"
#    shutil.rmtree(INDY_ETC)
#  run("mv %s %s" % (indyEtcDir, INDY_ETC), "Failed to move %s to %s" % (indyEtcDir, INDY_ETC))
  
# backup indy default promote scripts
if os.path.isdir(INDY_DATA_PROMOTE):
  if os.path.isdir(BACKUP_PROMOTE):
    print 'Remove old promote backup %s' % BACKUP_PROMOTE
    shutil.rmtree(BACKUP_PROMOTE)
  print 'Backup new promote %s' % BACKUP_PROMOTE
  shutil.copytree(INDY_DATA_PROMOTE, BACKUP_PROMOTE)

# move_and_link(INDY_ETC, ETC_INDY, replaceIfExists=True)
# move_and_link(INDY_STORAGE, VAR_STORAGE)
# move_and_link(INDY_DATA, VAR_DATA)
# move_and_link(INDY_LOGS, VAR_LOGS)

##################################################################
## CUSTOM FOR PNC: Copy overwritten things unique to this image ##
##################################################################

# copy_over("/usr/share/indy/promote", INDY_DATA_PROMOTE)
# copy_over("/usr/share/indy/scripts", os.path.join(INDY_DATA, "scripts"))
# copy_missed("/opt/indy/etc/indy/promote", INDY_DATA_PROMOTE)
# copy_missed("/usr/share/indy/data/promote/rules", os.path.join(INDY_DATA_PROMOTE, "rules"))
copy_over("/opt/indy/etc/indy/scripts", os.path.join(INDY_DATA, "scripts"))
copy_over("/opt/indy/etc/indy/lifecycle", os.path.join(INDY_DATA, "lifecycle"))

# copy_missed(BACKUP_PROMOTE, INDY_DATA_PROMOTE)

cmd_parts = ["/bin/bash", os.path.join(INDY_DIR, 'bin', 'indy.sh')]
cmd_parts += shlex.split(opts)

print "Command parts: %s" % cmd_parts
process = subprocess.Popen(cmd_parts, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

signal.signal(signal.SIGTERM, handle_shutdown)

handle_output(process)
