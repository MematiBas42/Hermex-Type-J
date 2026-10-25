#!/usr/bin/env python3
import os
import re
import json

ROOT = '/home/ubuntu/projects/Hermex-Type-J'
SWIFT_BASE = os.path.join(ROOT, 'HermesMobile')
KT_BASE = os.path.join(ROOT, 'android/app/src/main/java')

# 1. Load all Kotlin files
kt_files = {}
for root, _, fnames in os.walk(KT_BASE):
    for f in fnames:
        if f.endswith('.kt'):
            p = os.path.join(root, f)
            rel = os.path.relpath(p, KT_BASE)
            kt_files[rel] = open(p, 'r', errors='ignore').read()

def find_kotlin_counterpart(name, swift_file):
    # exact composable or class
    pat_fun = f"fun {name}("
    pat_cls = f"class {name}"
    pat_port1 = f"Port of `{name}`"
    pat_port2 = f"Port of `{name}.swift`"
    
    # 1. direct fun or class
    for rel, content in kt_files.items():
        if pat_fun in content or pat_cls in content:
            return rel, "Direct Port"
            
    # 2. explicit port annotation matching name or swift file
    base_swift = os.path.basename(swift_file)
    pat_file = f"Port of `{base_swift}`"
    for rel, content in kt_files.items():
        if pat_port1 in content or pat_port2 in content or pat_file in content:
            return rel, "Port Annotated"
            
    # 3. name token search
    for rel, content in kt_files.items():
        if re.search(r'\b' + re.escape(name) + r'\b', content):
            return rel, "Referenced"
            
    return "None (Missing in Android)", "Missing"

elements = []

# Scan HermesMobile
for root, _, fnames in sorted(os.walk(SWIFT_BASE)):
    for f in sorted(fnames):
        if f.endswith('.swift'):
            path = os.path.join(root, f)
            rel = os.path.relpath(path, SWIFT_BASE)
            content = open(path, 'r', errors='ignore').read()
            
            # Category
            parts = rel.split(os.sep)
            if parts[0] == 'Features':
                category = parts[1]
                if category == 'Workspace' and len(parts) > 2 and parts[2] in ('ReviewDiff', 'SourceViewer'):
                    category = f"Workspace/{parts[2]}"
            elif parts[0] in ('Auth', 'Config', 'LiveActivities', 'AppIntents', 'Models', 'Networking', 'Persistence', 'Push'):
                category = parts[0]
            else:
                category = 'AppRoot'
                
            for m in re.finditer(r'(?:public\s+|private\s+|internal\s+)?struct\s+([A-Za-z0-9_]+)\s*:\s*View\b', content):
                name = m.group(1)
                kt_target, status = find_kotlin_counterpart(name, rel)
                elements.append({
                    'type': 'View',
                    'name': name,
                    'swift_file': rel,
                    'category': category,
                    'kt_target': kt_target,
                    'port_status': status
                })
                
            for m in re.finditer(r'(?:public\s+|private\s+|internal\s+)?struct\s+([A-Za-z0-9_]+)\s*:\s*ViewModifier\b', content):
                name = m.group(1)
                kt_target, status = find_kotlin_counterpart(name, rel)
                elements.append({
                    'type': 'ViewModifier',
                    'name': name,
                    'swift_file': rel,
                    'category': category,
                    'kt_target': kt_target,
                    'port_status': status
                })

# Scan HermesLiveActivityWidget
w_base = os.path.join(ROOT, 'HermesLiveActivityWidget')
if os.path.exists(w_base):
    for root, _, fnames in sorted(os.walk(w_base)):
        for f in sorted(fnames):
            if f.endswith('.swift'):
                path = os.path.join(root, f)
                rel = os.path.relpath(path, ROOT)
                content = open(path, 'r', errors='ignore').read()
                for m in re.finditer(r'(?:public\s+|private\s+|internal\s+)?struct\s+([A-Za-z0-9_]+)\s*:\s*Widget\b', content):
                    name = m.group(1)
                    kt_target, status = find_kotlin_counterpart(name, rel)
                    elements.append({
                        'type': 'Widget',
                        'name': name,
                        'swift_file': rel,
                        'category': 'LiveActivities',
                        'kt_target': kt_target if status != 'Missing' else 'service/AgentRunService.kt (Glance Widget Pending)',
                        'port_status': 'Platform Equivalent'
                    })
                for m in re.finditer(r'(?:public\s+|private\s+|internal\s+)?struct\s+([A-Za-z0-9_]+)\s*:\s*View\b', content):
                    name = m.group(1)
                    kt_target, status = find_kotlin_counterpart(name, rel)
                    elements.append({
                        'type': 'LiveActivityView',
                        'name': name,
                        'swift_file': rel,
                        'category': 'LiveActivities',
                        'kt_target': kt_target if status != 'Missing' else 'service/AgentRunService.kt (Notification RemoteViews)',
                        'port_status': 'Platform Equivalent'
                    })

# Scan AppIntents
ai_base = os.path.join(SWIFT_BASE, 'AppIntents')
if os.path.exists(ai_base):
    for f in sorted(os.listdir(ai_base)):
        if f.endswith('.swift'):
            path = os.path.join(ai_base, f)
            rel = os.path.relpath(path, SWIFT_BASE)
            content = open(path, 'r', errors='ignore').read()
            for m in re.finditer(r'(?:public\s+|private\s+|internal\s+)?struct\s+([A-Za-z0-9_]+)\s*:\s*AppIntent\b', content):
                name = m.group(1)
                elements.append({
                    'type': 'AppIntent',
                    'name': name,
                    'swift_file': rel,
                    'category': 'AppIntents',
                    'kt_target': 'MainActivity.kt / shortcuts.xml',
                    'port_status': 'Platform Equivalent'
                })
            for m in re.finditer(r'(?:public\s+|private\s+|internal\s+)?struct\s+([A-Za-z0-9_]+)\s*:\s*AppShortcutsProvider\b', content):
                name = m.group(1)
                elements.append({
                    'type': 'AppShortcutsProvider',
                    'name': name,
                    'swift_file': rel,
                    'category': 'AppIntents',
                    'kt_target': 'res/xml/shortcuts.xml',
                    'port_status': 'Platform Equivalent'
                })

# Scan HermesShareExtension
se_base = os.path.join(ROOT, 'HermesShareExtension')
if os.path.exists(se_base):
    for root, _, fnames in sorted(os.walk(se_base)):
        for f in sorted(fnames):
            if f.endswith('.swift'):
                path = os.path.join(root, f)
                rel = os.path.relpath(path, ROOT)
                content = open(path, 'r', errors='ignore').read()
                for m in re.finditer(r'(?:class|struct|enum)\s+([A-Za-z0-9_]+)', content):
                    name = m.group(1)
                    if name in ('ShareViewController', 'ShareInputReader'):
                        elements.append({
                            'type': 'ShareExtension',
                            'name': name,
                            'swift_file': rel,
                            'category': 'ShareExtension',
                            'kt_target': 'MainActivity.kt (ACTION_SEND filter)',
                            'port_status': 'Platform Equivalent'
                        })

# Scan HermesNotificationService
ns_base = os.path.join(ROOT, 'HermesNotificationService')
if os.path.exists(ns_base):
    for root, _, fnames in sorted(os.walk(ns_base)):
        for f in sorted(fnames):
            if f.endswith('.swift'):
                path = os.path.join(root, f)
                rel = os.path.relpath(path, ROOT)
                content = open(path, 'r', errors='ignore').read()
                for m in re.finditer(r'class\s+([A-Za-z0-9_]+)', content):
                    name = m.group(1)
                    elements.append({
                        'type': 'NotificationService',
                        'name': name,
                        'swift_file': rel,
                        'category': 'Push',
                        'kt_target': 'service/HermexFirebaseMessagingService.kt',
                        'port_status': 'Platform Equivalent'
                    })

output_path = os.path.join(ROOT, 'scripts/ios_elements.json')
with open(output_path, 'w') as out:
    json.dump(elements, out, indent=2)

print(f"Total elements recorded: {len(elements)}")
by_cat = {}
for e in elements:
    by_cat[e['category']] = by_cat.get(e['category'], 0) + 1
for cat, count in sorted(by_cat.items()):
    print(f"  - {cat:25}: {count:3} elements")
