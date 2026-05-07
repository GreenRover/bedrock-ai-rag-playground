import os
import re
from pathlib import Path

# Configuration
EXPORT_DIR = Path("../messaging_support_export")
INDEX_FILE = EXPORT_DIR / "00_index.md"

def parse_index():
    """
    Parses 00_index.md to build a mapping of filename -> title_path
    Assumes standard markdown list hierarchy:
    * [Title](filename.md)
      * [Child Title](child_filename.md)
    """
    mapping = {}

    # Try multiple index files as fallbacks
    index_files = [EXPORT_DIR / "00_index.md", EXPORT_DIR / "00_index_A001.md", EXPORT_DIR / "00_index_A002.md"]

    target_index = None
    for f in index_files:
        if f.exists() and f.stat().st_size > 100: # Simple heuristic to skip empty/header-only files
            target_index = f
            break

    if not target_index:
        print(f"Warning: No suitable index file found in {EXPORT_DIR}. Checked: {[f.name for f in index_files]}")
        return mapping

    print(f"Using index file: {target_index.name}")
    with open(target_index, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    path_stack = []  # Stores tuples of (indent_level, title)

    for line in lines:
        # Match list items with links: "  * [Title](123_Filename.md)"
        match = re.match(r'^(\s*)[\*\-]\s+\[([^\]]+)\]\(([^)]+)\)', line)
        if match:
            indent_str, title, filepath = match.groups()

            # Calculate indent level (treating tab as 4 spaces)
            indent_level = len(indent_str.replace('\t', '    '))

            # Adjust stack based on indentation
            while path_stack and path_stack[-1][0] >= indent_level:
                path_stack.pop()

            path_stack.append((indent_level, title))

            # Construct title path: "Home > Parent > Child"
            title_path = " > ".join([item[1] for item in path_stack])

            # Map just the filename (ignoring any folders in the link)
            # Handle cases like [Title](./filename.md | confluence_url)
            clean_filepath = filepath.split('|')[0].strip()
            filename = os.path.basename(clean_filepath)
            mapping[filename] = title_path

    return mapping

def process_markdown_files(mapping):
    """
    Iterates over the exported markdown files, updates frontmatter, and formats asset links.
    """
    # Regex 1: Matches standard markdown images -> ![alt text](path/to/image.png)
    md_img_regex = re.compile(r'!\[.*?\]\((?:.*?/)?([^)]+)\)')

    # Regex 2: Matches HTML image tags -> <img src="path/to/image.png" />
    html_img_regex = re.compile(r'<img[^>]+src=["\'](?:[^"\']*/)?([^"\']+)["\'][^>]*>')

    # Regex 3: Matches regular markdown links to assets/attachments -> [text](assets/file.pdf)
    md_asset_link_regex = re.compile(r'\[.*?\]\((?:(?:\.\./)*assets|\.\/?assets|attachments)[^)]*/([^)]+)\)')

    updated_count = 0

    for file_path in EXPORT_DIR.glob("*.md"):
        if file_path.name == "00_index.md":
            continue

        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()

        new_content = content

        # --- 1. UPDATE FRONTMATTER ---
        title_path = mapping.get(file_path.name)

        # Look for the YAML frontmatter block starting at the top of the file
        fm_match = re.match(r'^(---\n.*?\n)---\n', new_content, re.DOTALL)

        if fm_match and title_path:
            frontmatter = fm_match.group(1)
            body = new_content[fm_match.end():]

            if 'title_path:' not in frontmatter:
                safe_title_path = title_path.replace("'", "''")
                new_fm_line = f"title_path: '{safe_title_path}'\n"

                # Insert title_path immediately after title
                if 'title:' in frontmatter:
                    frontmatter = re.sub(r'(title: .*?\n)', r'\1' + new_fm_line, frontmatter, count=1)
                else:
                    frontmatter += new_fm_line

            new_content = f"{frontmatter}---\n{body}"

        # --- 2. UPDATE ASSET LINKS ---
        # Replace ![alt](...) with [[ATTACHMENT:filename]]
        new_content = md_img_regex.sub(r'[[ATTACHMENT:\1]]', new_content)

        # Replace <img src="..."> with [[ATTACHMENT:filename]]
        new_content = html_img_regex.sub(r'[[ATTACHMENT:\1]]', new_content)

        # Replace [link text](assets/...) with [[ATTACHMENT:filename]]
        new_content = md_asset_link_regex.sub(r'[[ATTACHMENT:\1]]', new_content)

        # Save back to file if changes were made
        if new_content != content:
            with open(file_path, 'w', encoding='utf-8') as f:
                f.write(new_content)
            print(f"Updated: {file_path.name}")
            updated_count += 1

    print(f"\nProcessing complete! Modified {updated_count} files.")

if __name__ == "__main__":
    if not EXPORT_DIR.exists():
        print(f"Error: Directory '{EXPORT_DIR}' does not exist.")
        exit(1)

    print("Parsing index...")
    title_mapping = parse_index()
    print(f"Found {len(title_mapping)} entries in index.")

    print("Processing markdown files...")
    process_markdown_files(title_mapping)