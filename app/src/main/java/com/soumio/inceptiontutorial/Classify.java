package com.soumio.inceptiontutorial;

import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public class Classify extends AppCompatActivity {


    private static final int RESULTS_TO_SHOW = 3;
    private static final int IMAGE_MEAN = 128;
    private static final float IMAGE_STD = 128.0f;


    private final Interpreter.Options tfliteOptions = new Interpreter.Options();

    private Interpreter tflite;
    private List<String> labelList;
    private ByteBuffer imgData = null;
    private float[][] labelProbArray = null;
    private byte[][] labelProbArrayB = null;
    private String[] topLables = null;
    private String[] topConfidence = null;


    private String chosen;
    private boolean quant;

    private int DIM_IMG_SIZE_X = 299;
    private int DIM_IMG_SIZE_Y = 299;
    private int DIM_PIXEL_SIZE = 3;

    private int[] intValues;

    private ImageView selected_image;
    private Button classify_button;
    private Button back_button;
    private TextView diseaseText;
    private TextView symptomsText;
    private TextView treatmentText;
    private LinearLayout labels;

    private PriorityQueue<Map.Entry<String, Float>> sortedLabels =
            new PriorityQueue<>(
                    RESULTS_TO_SHOW,
                    new Comparator<Map.Entry<String, Float>>() {
                        @Override
                        public int compare(Map.Entry<String, Float> o1, Map.Entry<String, Float> o2) {
                            return (o1.getValue()).compareTo(o2.getValue());
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        chosen = (String) getIntent().getStringExtra("chosen");
        quant = (boolean) getIntent().getBooleanExtra("quant", false);

        intValues = new int[DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y];

        super.onCreate(savedInstanceState);

        try{
            tflite = new Interpreter(loadModelFile(), tfliteOptions);
            labelList = loadLabelList();
        } catch (Exception ex){
            ex.printStackTrace();
        }

        if(quant){
            imgData =
                    ByteBuffer.allocateDirect(
                            DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y * DIM_PIXEL_SIZE);
        } else {
            imgData =
                    ByteBuffer.allocateDirect(
                            4 * DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y * DIM_PIXEL_SIZE);
        }
        imgData.order(ByteOrder.nativeOrder());

        if(quant){
            labelProbArrayB= new byte[1][labelList.size()];
        } else {
            labelProbArray = new float[1][labelList.size()];
        }

        setContentView(R.layout.activity_classify);


        diseaseText= findViewById(R.id.DiseaseNameText);
        symptomsText= findViewById(R.id.SymptomsText);
        treatmentText= findViewById(R.id.TreatmentText);

        labels= findViewById(R.id.labels);

        selected_image = (ImageView) findViewById(R.id.selected_image);

        topLables = new String[RESULTS_TO_SHOW];
        topConfidence = new String[RESULTS_TO_SHOW];

        back_button = findViewById(R.id.back_button);
        back_button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent i = new Intent(Classify.this, ChooseModel.class);
                startActivity(i);
            }
        });

        classify_button = findViewById(R.id.classify_image);
        classify_button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Bitmap bitmap_orig = ((BitmapDrawable)selected_image.getDrawable()).getBitmap();
                Bitmap bitmap = getResizedBitmap(bitmap_orig, DIM_IMG_SIZE_X, DIM_IMG_SIZE_Y);
                convertBitmapToByteBuffer(bitmap);
                if(quant){
                    tflite.run(imgData, labelProbArrayB);
                } else {
                    tflite.run(imgData, labelProbArray);
                }
                printTopKLabels();

                labels.setVisibility(View.VISIBLE);
            }
        });

        Uri uri = (Uri)getIntent().getParcelableExtra("resID_uri");
        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
            selected_image.setImageBitmap(bitmap);
            selected_image.setRotation(selected_image.getRotation() + 90);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private MappedByteBuffer loadModelFile() throws IOException {
        AssetFileDescriptor fileDescriptor = this.getAssets().openFd(chosen);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    private void convertBitmapToByteBuffer(Bitmap bitmap) {
        if (imgData == null) {
            return;
        }
        imgData.rewind();
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        int pixel = 0;
        for (int i = 0; i < DIM_IMG_SIZE_X; ++i) {
            for (int j = 0; j < DIM_IMG_SIZE_Y; ++j) {
                final int val = intValues[pixel++];
                if(quant){
                    imgData.put((byte) ((val >> 16) & 0xFF));
                    imgData.put((byte) ((val >> 8) & 0xFF));
                    imgData.put((byte) (val & 0xFF));
                } else {
                    imgData.putFloat((((val >> 16) & 0xFF)-IMAGE_MEAN)/IMAGE_STD);
                    imgData.putFloat((((val >> 8) & 0xFF)-IMAGE_MEAN)/IMAGE_STD);
                    imgData.putFloat((((val) & 0xFF)-IMAGE_MEAN)/IMAGE_STD);
                }

            }
        }
    }

    private List<String> loadLabelList() throws IOException {
        List<String> labelList = new ArrayList<String>();
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(this.getAssets().open("retrained_labels.txt")));
        String line;
        while ((line = reader.readLine()) != null) {
            labelList.add(line);
        }
        reader.close();
        return labelList;
    }

    private void printTopKLabels() {
        // add all results to priority queue
        for (int i = 0; i < labelList.size(); ++i) {
            if(quant){
                sortedLabels.add(
                        new AbstractMap.SimpleEntry<>(labelList.get(i), (labelProbArrayB[0][i] & 0xff) / 255.0f));
            } else {
                sortedLabels.add(
                        new AbstractMap.SimpleEntry<>(labelList.get(i), labelProbArray[0][i]));
            }
            if (sortedLabels.size() > RESULTS_TO_SHOW) {
                sortedLabels.poll();
            }
        }

        final int size = sortedLabels.size();
        for (int i = 0; i < size; ++i) {
            Map.Entry<String, Float> label = sortedLabels.poll();
            topLables[i] = label.getKey();
            topConfidence[i] = String.valueOf(label.getValue());

        }

        for (int i = 0; i<size; ++i){
            if(Double.parseDouble(topConfidence[i])>=0.55){
                String disease= topLables[i];
                String symptoms= topLables[i]+"_Symptomps";
                String treatment= topLables[i]+"_Treatment";

                int disease_id= getResources().getIdentifier(disease,"string",getPackageName());
                int symptom_id= getResources().getIdentifier(symptoms,"string",getPackageName());
                int treatment_id= getResources().getIdentifier(treatment,"string",getPackageName());

                diseaseText.setText(getResources().getString(disease_id));
                symptomsText.setText(getResources().getString(symptom_id));
                treatmentText.setText(getResources().getString(treatment_id));
//                Toast.makeText(this,topLables[i]+" Highest",Toast.LENGTH_LONG).show();
            }

        }


    }


    public Bitmap getResizedBitmap(Bitmap bm, int newWidth, int newHeight) {
        int width = bm.getWidth();
        int height = bm.getHeight();
        float scaleWidth = ((float) newWidth) / width;
        float scaleHeight = ((float) newHeight) / height;
        Matrix matrix = new Matrix();
        matrix.postScale(scaleWidth, scaleHeight);
        Bitmap resizedBitmap = Bitmap.createBitmap(
                bm, 0, 0, width, height, matrix, false);
        return resizedBitmap;
    }
}
