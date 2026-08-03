import type { CameraView } from '@/types/api';
import type { MapEndpointTarget, MapSelection } from '@/types/map';

function coordinateText(coordinate: { lng: number; lat: number }) {
  return `坐标：${coordinate.lng.toFixed(6)}, ${coordinate.lat.toFixed(6)}`;
}

function textLine(text: string, className?: string) {
  const line = document.createElement('span');
  if (className) line.className = className;
  line.textContent = text;
  return line;
}

export function createCameraPopup(camera: CameraView) {
  const content = document.createElement('div');
  content.className = 'map-info-popup camera-popup';
  content.dataset.testid = 'camera-popup';

  const title = document.createElement('strong');
  title.textContent = camera.cameraType;
  content.append(
    title,
    textLine(camera.address),
    textLine(camera.directionText ? `方向：${camera.directionText}` : '方向：未标注'),
    textLine(coordinateText(camera), 'map-info-popup__coordinate'),
  );
  return content;
}

export function createMapPointPopup(
  selection: MapSelection,
  onSelect: (target: MapEndpointTarget) => void,
) {
  const content = document.createElement('div');
  content.className = 'map-info-popup map-point-popup';
  content.dataset.testid = 'map-point-popup';

  const title = document.createElement('strong');
  title.textContent = selection.suggestedName;
  const actions = document.createElement('div');
  actions.className = 'map-point-popup__actions';

  const createAction = (target: MapEndpointTarget, label: string) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = `map-point-popup__action map-point-popup__action--${target}`;
    button.dataset.testid = `set-${target}-from-map`;
    button.textContent = label;
    button.addEventListener('pointerdown', (event) => event.stopPropagation());
    button.addEventListener('click', (event) => {
      event.stopPropagation();
      onSelect(target);
    });
    return button;
  };

  actions.append(createAction('start', '设为起点'), createAction('end', '设为终点'));
  content.append(
    title,
    textLine(coordinateText(selection.coordinate), 'map-info-popup__coordinate'),
    actions,
  );
  return content;
}
