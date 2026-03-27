import os


def process(path, name, extensions):
    final = ""

    for root, dirs, files in os.walk(path):
        # 1. Filter files based on the passed extensions tuple
        for file in [f for f in files if f.endswith(extensions)]:

            # Logic to skip specific files
            if file in [".DS_Store"]:
                continue

            # Construct the full absolute path
            full_path = os.path.join(root, file)

            # Construct a relative path
            relative_path = os.path.relpath(full_path, path)

            try:
                # 'errors="replace"' prevents crashing on weird characters
                with open(full_path, "r", encoding="utf-8", errors="replace") as f:
                    content = f.read()
                    final += f"{relative_path}:\n{content}\n\n"
            except Exception as e:
                print(f"Skipping {file} (likely not a text file or permission error).")

    with open("source-" + name + ".txt", "w", encoding="utf-8") as f:
        f.write(final.strip())


# Look for Java and Kotlin files in the typewriter folder
process(
    r"C:\Users\ethan\Documents\GitHub\Gnimble-Native-Android\app\src\main\java\com\gnimble\typewriter",
    "typewriter",
    (".java", ".kt"),
)

# Look for XML files in the res folder
process(
    r"C:\Users\ethan\Documents\GitHub\Gnimble-Native-Android\app\src\main\res",
    "res",
    (".xml",),
)
