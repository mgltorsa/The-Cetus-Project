import os
import sys
import pandas as pd

def merge_csv_files(folder_path):
    # Get a list of all CSV files in the folder
    csv_files = [file for file in os.listdir(folder_path) if file.endswith('.csv')]

    # Initialize an empty DataFrame to store the merged data
    merged_data = pd.DataFrame()

    # Loop through each CSV file and merge its data into the DataFrame
    for file in csv_files:
        if file == 'merged_data.csv':
            continue
        file_path = os.path.join(folder_path, file)
        data = pd.read_csv(file_path, names=["benchmark","function","subroutine","loopId","patterns"], sep=";")
        print(data)
        merged_data = pd.concat([merged_data, data], ignore_index=True)

    # Write the merged data to a new CSV file
    output_file = 'merged_data.csv'
    output_path = os.path.join(folder_path, output_file)
    if os.path.exists(output_path):
        os.remove(output_path)
    
    merged_data.to_csv(output_path, sep=';', index=False)

    print(f'Merged data saved to {output_path}')

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python merge_csv.py <folder_path>")
    else:
        folder_path = sys.argv[1]
        if not os.path.isdir(folder_path):
            print("Error: Invalid folder path")
        else:
            merge_csv_files(folder_path)
