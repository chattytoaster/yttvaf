with open("scratch/src/dev/cobalt/coat/ProxyHelper.java", "r", encoding="utf-8", errors="ignore") as f:
    text = f.read()

# Let's replace any broken characters with clean ASCII/Unicode
print("Length:", len(text))
