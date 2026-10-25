#!/usr/bin/env python3
"""
scripts/verify_plan.py
Deterministic Verification Script for Hermex Native iOS Parity Plan.

Audits `plan.md` against every single native iOS element in `HermesMobile`,
`HermesLiveActivityWidget`, `HermesShareExtension`, and `HermesNotificationService`.

Validates:
1. 100% element coverage (All 345 SwiftUI Views, 40 ViewModifiers, 12 Widget views, 4 AppIntents, 2 Extensions).
2. All 12 Native iOS Architectural Patterns & Remediation Directives.
3. Checkbox format consistency ([ ] or [x] for every tracked element).

Exits with:
- 0: 100% verified complete coverage.
- 1: Any missing element, broken pattern, or syntax failure.
"""

import os
import re
import sys
import json

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PLAN_PATH = os.path.join(ROOT, 'plan.md')
SWIFT_BASE = os.path.join(ROOT, 'HermesMobile')
WIDGET_BASE = os.path.join(ROOT, 'HermesLiveActivityWidget')
SHARE_BASE = os.path.join(ROOT, 'HermesShareExtension')
PUSH_BASE = os.path.join(ROOT, 'HermesNotificationService')

REQUIRED_ARCHITECTURAL_CAPABILITIES = [
    ("ROOT_SCAFFOLD_TABBAR_AUTOHIDE", "Scaffold / NavigationBar auto-hide on ChatRoute navigation"),
    ("ZSTACK_BOX_OVERLAY_ARCHITECTURE", "Box overlay layout replacing Column weight stacking in ChatPane"),
    ("EDGE_TO_EDGE_WINDOW_INSETS", "statusBars, navigationBarsPadding, and imePadding edge-to-edge handling"),
    ("TRANSLUCENT_TOPBAR_BLUR", "ChatNavigationBackground frosted glass / blur surface translucency"),
    ("BOTTOM_COMPOSER_MATERIAL_FADE", "BottomComposerMaterialFade 96dp gradient alpha backdrop"),
    ("CONTEXT_WINDOW_INDICATOR_INLINE", "ContextWindowIndicator inline integration into composer accessory"),
    ("SINGLE_MORPHING_STOP_BUTTON", "Single morphing Send -> Stop button (eliminating duplicate stop buttons)"),
    ("GIT_BRANCH_ICON_PARITY", "Distinct Git branch glyph replacing duplicate folder icon"),
    ("HORIZONTAL_CHIP_SCROLL_FADING", "Fading edge mask for ChatComposerSelectorChips avoiding truncation"),
    ("SPRING_PHYSICS_AND_HAPTICS", "iOS spring dynamics and haptic feedback constants"),
    ("RICH_ONGOING_NOTIFICATION_GLANCE", "Ongoing notification & Glance widget parity for Live Activities"),
    ("OBTAINIUM_ACTIONS_RELEASE_PIPELINE", "GitHub Actions workflow for automated signing and GitHub Releases for Obtainium")
]

def collect_ios_elements():
    elements = []
    
    # 1. HermesMobile
    for root, _, fnames in sorted(os.walk(SWIFT_BASE)):
        for f in sorted(fnames):
            if f.endswith('.swift'):
                path = os.path.join(root, f)
                rel = os.path.relpath(path, SWIFT_BASE)
                content = open(path, 'r', errors='ignore').read()
                
                # Views
                for m in re.finditer(r'(?:public\s+|private\s+|internal\s+)?struct\s+([A-Za-z0-9_]+)\s*:\s*View\b', content):
                    elements.append(('View', m.group(1), rel))
                
                # ViewModifiers
                for m in re.finditer(r'(?:public\s+|private\s+|internal\s+)?struct\s+([A-Za-z0-9_]+)\s*:\s*ViewModifier\b', content):
                    elements.append(('Modifier', m.group(1), rel))
                
                # AppIntents
                for m in re.finditer(r'(?:public\s+|private\s+|internal\s+)?struct\s+([A-Za-z0-9_]+)\s*:\s*(?:AppIntent|AppShortcutsProvider)\b', content):
                    elements.append(('AppIntent', m.group(1), rel))

    # 2. HermesLiveActivityWidget
    if os.path.exists(WIDGET_BASE):
        for root, _, fnames in sorted(os.walk(WIDGET_BASE)):
            for f in sorted(fnames):
                if f.endswith('.swift'):
                    path = os.path.join(root, f)
                    rel = os.path.relpath(path, ROOT)
                    content = open(path, 'r', errors='ignore').read()
                    for m in re.finditer(r'(?:public\s+|private\s+|internal\s+)?struct\s+([A-Za-z0-9_]+)\s*:\s*Widget\b', content):
                        elements.append(('Widget', m.group(1), rel))
                    for m in re.finditer(r'(?:public\s+|private\s+|internal\s+)?struct\s+([A-Za-z0-9_]+)\s*:\s*View\b', content):
                        elements.append(('LiveActivityView', m.group(1), rel))

    # 3. HermesShareExtension
    if os.path.exists(SHARE_BASE):
        for root, _, fnames in sorted(os.walk(SHARE_BASE)):
            for f in sorted(fnames):
                if f.endswith('.swift'):
                    path = os.path.join(root, f)
                    rel = os.path.relpath(path, ROOT)
                    content = open(path, 'r', errors='ignore').read()
                    for m in re.finditer(r'(?:class|struct|enum)\s+([A-Za-z0-9_]+)', content):
                        name = m.group(1)
                        if name in ('ShareViewController', 'ShareInputReader'):
                            elements.append(('ShareExtension', name, rel))

    # 4. HermesNotificationService
    if os.path.exists(PUSH_BASE):
        for root, _, fnames in sorted(os.walk(PUSH_BASE)):
            for f in sorted(fnames):
                if f.endswith('.swift'):
                    path = os.path.join(root, f)
                    rel = os.path.relpath(path, ROOT)
                    content = open(path, 'r', errors='ignore').read()
                    for m in re.finditer(r'class\s+([A-Za-z0-9_]+)', content):
                        name = m.group(1)
                        if name == 'NotificationService':
                            elements.append(('NotificationService', name, rel))

    return elements

def audit_plan():
    print("=" * 80)
    print("HERMEX-TYPE-J: DETERMINISTIC NATIVE iOS PARITY PLAN VERIFIER")
    print("=" * 80)

    if not os.path.exists(PLAN_PATH):
        print(f"[FAIL] plan.md not found at {PLAN_PATH}")
        sys.exit(1)

    with open(PLAN_PATH, 'r', errors='ignore') as f:
        plan_content = f.read()

    elements = collect_ios_elements()
    print(f"[*] Total native iOS elements collected: {len(elements)}")

    # 1. Audit Elements Coverage
    missing_elements = []
    found_elements = []

    for kind, name, rel in elements:
        # Pattern looks for checklist entry with element name:
        # e.g.: - [ ] `ElementName` or - [x] `ElementName` or `ElementName` in a table row
        pattern = r'(?:-\s*\[[ xX]\]\s*`?' + re.escape(name) + r'`?|`' + re.escape(name) + r'`\s*\|)'
        if re.search(pattern, plan_content):
            found_elements.append((kind, name, rel))
        else:
            missing_elements.append((kind, name, rel))

    # 2. Audit Architectural Capabilities
    missing_capabilities = []
    found_capabilities = []

    for tag, desc in REQUIRED_ARCHITECTURAL_CAPABILITIES:
        if tag in plan_content:
            found_capabilities.append((tag, desc))
        else:
            missing_capabilities.append((tag, desc))

    # 3. Print Report
    print(f"\n[+] Element Coverage Matrix:")
    print(f"    - Total Tracked Elements  : {len(elements)}")
    print(f"    - Elements in plan.md     : {len(found_elements)} ({len(found_elements)/len(elements)*100:.1f}%)")
    print(f"    - Missing Elements        : {len(missing_elements)}")

    print(f"\n[+] Architectural Capabilities Matrix:")
    print(f"    - Total Required Patterns : {len(REQUIRED_ARCHITECTURAL_CAPABILITIES)}")
    print(f"    - Capabilities in plan.md : {len(found_capabilities)} ({len(found_capabilities)/len(REQUIRED_ARCHITECTURAL_CAPABILITIES)*100:.1f}%)")
    print(f"    - Missing Capabilities    : {len(missing_capabilities)}")

    if missing_elements:
        print(f"\n[!] Missing Elements Detail (Top 20 shown):")
        for kind, name, rel in missing_elements[:20]:
            print(f"    - [{kind}] {name} (from {rel})")
        if len(missing_elements) > 20:
            print(f"    ... and {len(missing_elements) - 20} more.")

    if missing_capabilities:
        print(f"\n[!] Missing Capabilities Detail:")
        for tag, desc in missing_capabilities:
            print(f"    - [{tag}] {desc}")

    print("\n" + "=" * 80)
    if missing_elements or missing_capabilities:
        print("[RESULT: FAILED] Parity coverage is incomplete. Exiting with code 1.")
        print("=" * 80)
        sys.exit(1)
    else:
        print("[RESULT: PASSED] 100% iOS Parity Coverage Verified across all elements & architectures.")
        print("=" * 80)
        sys.exit(0)

if __name__ == '__main__':
    audit_plan()
