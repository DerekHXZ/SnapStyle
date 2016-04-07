package edu.columbia.cs.snapstyle;

import android.app.AlertDialog;
import android.content.CursorLoader;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;

import com.android.internal.http.multipart.MultipartEntity;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_TAKE_PHOTO = 1;

    static {
        System.loadLibrary("opencv_java3");
    }

    private File photoFile;
    private Bitmap img_done;

    @Override
    protected void onCreate(Bundle savedInstanceState) {


        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                takePhoto();
            }
        });

        Button confirm = (Button) findViewById(R.id.confirm);
        confirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new Thread() {
                    @Override
                    public void run() {
                        processPhoto();
                    }
                }.start();
            }
        });

        Button upload = (Button) findViewById(R.id.upload);
        upload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new Thread() {
                    @Override
                    public void run() {
                        try {
                            uploadPhoto();
                        } catch (IOException e) {
                        }
                    }
                }.start();
            }
        });

    }

    private void uploadPhoto() throws IOException {
        if (img_done == null) {
            return;
        }

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        img_done.compress(Bitmap.CompressFormat.PNG, 100, os);
        byte[] png = os.toByteArray();

    }

    private void processPhoto() {
        if (photoFile == null) {
            return;
        }

        Mat img = Imgcodecs.imread(photoFile.getAbsolutePath());
        Imgproc.resize(img, img, new Size(640, (int) (img.rows() / (img.cols() / 640.0))));

        Mat grayImg = new Mat();
        Mat detectedEdges = new Mat();
        Imgproc.cvtColor(img, grayImg, Imgproc.COLOR_BGR2GRAY);
        Imgproc.blur(grayImg, detectedEdges, new Size(3, 3));

        SeekBar threshold = (SeekBar) findViewById(R.id.threshold);

        int progress = threshold.getProgress();
        Log.i("SnapStyle", "Value = " + progress);
        Imgproc.Canny(detectedEdges, detectedEdges, progress, progress * 3, 3, false);

        Mat lines = new Mat();
        Imgproc.HoughLinesP(detectedEdges, lines, 1, Math.PI / 180, 100, 100, 100);

        Log.i("SnapStyle", "Lines = " + lines.cols() + " " + lines.rows());

        // Find 4 best edges
        ArrayList<Edge> edges = new ArrayList<>();
        for (int x = 0; x < lines.rows(); x++) {
            double[] vec = lines.get(x, 0);
            double x1 = vec[0],
                    y1 = vec[1],
                    x2 = vec[2],
                    y2 = vec[3];


            Point start = new Point(x1, y1);
            Point end = new Point(x2, y2);

            edges.add(new Edge(start, end));
        }
        Collections.sort(edges);
        ArrayList<Edge> results = new ArrayList<>();
        for (Edge e: edges) {
            int q = 0;
            for (Edge f: results) {
                double angle = e.angle(f);
                if (angle < Math.PI / 18) {
                    q ++ ;
                    if (angle < Math.PI/180) {
                        q ++;
                    }
                }
            }
            if (q <= 1) {
                results.add(e);
                if (results.size() == 4) {
                    break;
                }
            }
        }

        if (results.size() < 4) {
            new AlertDialog.Builder(this)
                    .setTitle("Error")
                    .setMessage("Cannot detect at least 4 edges")
                    .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                        }
                    })
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .show();
            for (Edge e: edges) {
                Imgproc.line(img, e.start, e.end, new Scalar(0,255,0), 5);
            }
        } else {
            // Find 4 intersects
            ArrayList<Point> pts = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                for (int j = i+1; j < 4; j++) {
                    Point p = results.get(i).intersect(results.get(j));
                    if (p != null && p.x >= 0 && p.x <= img.cols() && p.y >=0 && p.y <= img.cols()) {
                        pts.add(p);
                    }
                }
            }
            if (pts.size() != 4) {
                new AlertDialog.Builder(this)
                        .setTitle("Error")
                        .setMessage("Cannot detect vertexes")
                        .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                            }
                        })
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .show();
                for (Point p: pts) {
                    Imgproc.circle(img, p, 5, new Scalar(0, 255, 0), 5);
                }
            } else {
                Point a = null, b = null, c = null, d = null;
                double ctrx = img.cols() / 2.0, ctry = img.rows() / 2.0;
                for (int i = 0; i < 4; i++) {
                    Point p = pts.get(i);
                    double ang = Math.atan2(p.y - ctry, p.x - ctrx);
                    if (ang >= Math.PI / 2) {
                        d = p;
                    } else if (ang >= 0) {
                        c = p;
                    } else if (ang >= -Math.PI / 2) {
                        b = p;
                    } else {
                        a = p;
                    }
                }

                Mat src = new Mat(4, 1, CvType.CV_32FC2);
                src.put(0, 0, a.x, a.y, b.x, b.y, c.x, c.y, d.x, d.y);

                Mat dst = new Mat(4, 1, CvType.CV_32FC2);
                dst.put(0, 0, 0, 0, img.cols(), 0, img.cols(), img.rows(), 0, img.rows());
                Mat M = Imgproc.getPerspectiveTransform(src, dst);
                Imgproc.warpPerspective(img, img, M, img.size());
            }

        }

        Bitmap bm = Bitmap.createBitmap(img.cols(), img.rows(), Bitmap.Config.ARGB_4444);
        Imgproc.cvtColor(img, img, Imgproc.COLOR_RGB2BGR);
        Utils.matToBitmap(img, bm);

        // find the imageview and draw it!
        ImageView iv = (ImageView) findViewById(R.id.image);
        iv.setImageBitmap(bm);

        img_done = bm;

    }

    private void takePhoto(){
        /*
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            // Create the File where the photo should go
            try {
                photoFile = createImageFile();
            } catch (IOException ex) {
                // Error occurred while creating the File
                Log.e("SnapStyle", "exception", ex);
            }
            // Continue only if the File was successfully created
            if (photoFile != null) {
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT,
                        Uri.fromFile(photoFile));
                startActivityForResult(takePictureIntent, REQUEST_TAKE_PHOTO);
            }
        }
        */
        Intent photoPickerIntent = new Intent(Intent.ACTION_PICK);
        photoPickerIntent.setType("image/*");
        startActivityForResult(photoPickerIntent, REQUEST_TAKE_PHOTO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_TAKE_PHOTO && resultCode == RESULT_OK) {
            //Show Photo
            ImageView iv = (ImageView) findViewById(R.id.image);

            Uri img = data.getData();
            photoFile = new File(getRealPathFromURI(img));

            iv.setImageURI(Uri.fromFile(photoFile));
            Button confirm = (Button) findViewById(R.id.confirm);
            confirm.setVisibility(View.VISIBLE);
            Button send = (Button) findViewById(R.id.upload);
            send.setVisibility(View.VISIBLE);
            SeekBar threshold = (SeekBar) findViewById(R.id.threshold);
            threshold.setVisibility(View.VISIBLE);
            WebView view = (WebView) findViewById(R.id.webView);
            view.setVisibility(View.INVISIBLE);
            img_done = null;
        }
    }

    private String mCurrentPhotoPath;
    private File createImageFile() throws IOException {
        // Create an image file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile(
                imageFileName,  /* prefix */
                ".jpg",         /* suffix */
                storageDir      /* directory */
        );

        // Save a file: path for use with ACTION_VIEW intents
        mCurrentPhotoPath = "file:" + image.getAbsolutePath();
        return image;
    }

    private class Edge implements Comparable<Edge> {
        public Point start;
        public Point end;
        public double d;

        Edge(Point s, Point e) {
            start = s; end = e;
            d = Math.sqrt(Math.pow(s.x - e.x, 2) + Math.pow(s.y - e.y, 2));
        }

        @Override
        public int compareTo(Edge another) {
            if (this.d < another.d) {
                return 1;
            } else if (this.d > another.d) {
                return -1;
            } else {
                return 0;
            }
        }

        public double angle(Edge b) {
            Edge a = this;
            double x1 = a.end.x - a.start.x;
            double x2 = b.end.x - b.start.x;
            double y1 = a.end.y - a.start.y;
            double y2 = b.end.y - b.start.y;

            double alpha = Math.acos((x1 * x2 + y1 * y2) / (Math.sqrt(x1*x1 + y1*y1) * Math.sqrt(x2*x2 + y2*y2)));
            if (alpha > Math.PI/2) alpha = Math.PI - alpha;
            return alpha;
        }

        public Point intersect(Edge b) {
            Edge a = this;
            double denominator = (a.end.y - a.start.y) * (b.end.x - b.start.x) - (a.start.x - a.end.x) * (b.start.y - b.end.y);
            if (denominator == 0) {
                return null;
            }
            double x = ((a.end.x - a.start.x) * (b.end.x - b.start.x) * (b.start.y - a.start.y)
                    + (a.end.y - a.start.y) * (b.end.x - b.start.x) * a.start.x
                    - (b.end.y - b.start.y) * (a.end.x - a.start.x) * b.start.x) / denominator;
            double y = -((a.end.y - a.start.y) * (b.end.y - b.start.y) * (b.start.x - a.start.x)
                    + (a.end.x - a.start.x) * (b.end.y - b.start.y) * a.start.y
                    - (b.end.x - b.start.x) * (a.end.y - a.start.y) * b.start.y) / denominator;
            return new Point(x, y);
        }
    }

    private String getRealPathFromURI(Uri contentUri) {
        String[] proj = { MediaStore.Images.Media.DATA };
        CursorLoader loader = new CursorLoader(this, contentUri, proj, null, null, null);
        Cursor cursor = loader.loadInBackground();
        int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
        cursor.moveToFirst();
        String result = cursor.getString(column_index);
        cursor.close();
        return result;
    }
}
