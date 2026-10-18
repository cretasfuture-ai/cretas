import shutil, socket, getpass
from pathlib import Path
MIN_FREE=125*2**30

def check_available(free):
 if type(free) is not int or free<MIN_FREE:
  raise RuntimeError('CI admission requires 125 GiB free to protect the 100 GiB reserve')

if __name__=='__main__':
 if (socket.gethostname(),getpass.getuser())!=('colima-ci','aurora'):
  raise RuntimeError('wrong CI VM identity')
 home=Path('/Users/aurora')
 if not home.is_dir():raise RuntimeError('host home mount unavailable')
 free=shutil.disk_usage(home).free;check_available(free)
 print('CRETAS_CAPACITY_OK free_gib=%.2f min_gib=125'%(free/2**30))
