"""Copy the versioned generic probe into a private evidence directory."""
import argparse,pathlib
p=argparse.ArgumentParser();p.add_argument('--previous-evidence',type=pathlib.Path);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
a.output.mkdir(parents=True,exist_ok=True)
target=a.output/'CompleteReadHttpProbe.java'
if target.exists():raise RuntimeError('Never overwrite a frozen probe')
target.write_bytes(pathlib.Path(__file__).with_name('CompleteReadHttpProbe.java').read_bytes())
print('Versioned8-Tool probe copied;local JWT refreshed per dialogue;actual actor fetched from readonly bridge')
