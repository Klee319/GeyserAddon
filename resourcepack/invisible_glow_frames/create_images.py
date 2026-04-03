"""
Script to create transparent PNG images for the invisible glow frames resource pack.
"""
from PIL import Image
import os

def create_transparent_png(filepath: str, width: int, height: int) -> None:
    """Create a fully transparent PNG image."""
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    img.save(filepath)
    print(f'Created {filepath} ({width}x{height} transparent)')

def create_pack_icon(filepath: str, size: int = 128) -> None:
    """Create a pack icon with a frame-like design."""
    icon = Image.new('RGBA', (size, size), (0, 0, 0, 0))

    # Define frame boundaries
    outer_margin = int(size * 0.0625)  # 8px for 128px
    frame_outer = int(size * 0.125)    # 16px for 128px
    frame_inner = int(size * 0.1875)   # 24px for 128px

    for x in range(size):
        for y in range(size):
            # Create a frame-like pattern
            in_outer_frame = (frame_outer <= x < size - frame_outer and
                            frame_outer <= y < size - frame_outer)
            in_inner_frame = (frame_inner <= x < size - frame_inner and
                            frame_inner <= y < size - frame_inner)
            in_border = (outer_margin <= x < size - outer_margin and
                        outer_margin <= y < size - outer_margin)

            if in_outer_frame:
                if in_inner_frame:
                    # Inner transparent area (the "glass" of the frame)
                    icon.putpixel((x, y), (0, 0, 0, 0))
                else:
                    # Frame border - golden/yellow color like glow
                    icon.putpixel((x, y), (255, 200, 50, 200))
            elif in_border:
                # Outer frame - darker wood color
                icon.putpixel((x, y), (139, 90, 43, 200))
            else:
                icon.putpixel((x, y), (0, 0, 0, 0))

    icon.save(filepath)
    print(f'Created {filepath} ({size}x{size} frame icon)')

def main() -> None:
    # Base directory for resource pack
    base_dir = os.path.dirname(os.path.abspath(__file__))

    # Create texture directories if they don't exist
    blocks_dir = os.path.join(base_dir, 'textures', 'blocks')
    entity_dir = os.path.join(base_dir, 'textures', 'entity')
    os.makedirs(blocks_dir, exist_ok=True)
    os.makedirs(entity_dir, exist_ok=True)

    # Create 16x16 transparent PNG for itemframe_background.png
    create_transparent_png(os.path.join(blocks_dir, 'itemframe_background.png'), 16, 16)

    # Create 16x16 transparent PNG for glow_itemframe.png
    create_transparent_png(os.path.join(blocks_dir, 'glow_itemframe.png'), 16, 16)

    # Create 64x64 transparent PNG for entity glow_item_frame.png
    create_transparent_png(os.path.join(entity_dir, 'glow_item_frame.png'), 64, 64)

    # Create pack_icon.png (128x128 with a simple design)
    create_pack_icon(os.path.join(base_dir, 'pack_icon.png'), 128)

    print('\nAll images created successfully!')

if __name__ == '__main__':
    main()
