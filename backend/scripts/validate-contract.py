"""Validate generated messages and every module of the actual reference containers."""
import json
from pathlib import Path
import zipfile
from jsonschema import Draft202012Validator

root = Path(__file__).resolve().parents[2] / 'docs' / 'backend'
schemas = {path.name: json.loads(path.read_text(encoding='utf-8')) for path in (root / 'schemas').glob('*.json')}
for schema in schemas.values():
    Draft202012Validator.check_schema(schema)
messages = 0
for path in (root / 'fixtures').glob('*.json'):
    name = ('model-catalog-request' if path.name.endswith('.catalog-request.json')
            else 'request' if path.name.endswith('.request.json') else 'reply')
    schema = schemas[f'{name}.schema.json']
    Draft202012Validator(schema).validate(json.loads(path.read_text(encoding='utf-8')))
    messages += 1
modules = 0
for path in (root / 'packages').glob('*.wordprism'):
    with zipfile.ZipFile(path) as archive:
        manifest = json.loads(archive.read('manifest.json'))
        Draft202012Validator(schemas['package-manifest.schema.json']).validate(manifest)
        for module in manifest['modules']:
            Draft202012Validator(schemas[f"module-{module['name']}.schema.json"]).validate(json.loads(archive.read(module['path'])))
            modules += 1
print(f'Validated {len(schemas)} schemas, {messages} messages and {modules} reference-package modules')
